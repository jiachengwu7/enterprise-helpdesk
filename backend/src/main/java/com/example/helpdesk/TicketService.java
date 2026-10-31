package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
// 手动创建和 AI 草稿确认最终共用此业务服务；AGENT 角色表示人类服务专员，不是 AI 模型。
public class TicketService {
  final BusinessRepository repo;
  final Locks locks;

  /**
   * 用途：注入工单持久化和并发控制依赖。
   * 输入：repo：业务仓储；locks：进程锁。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  TicketService(BusinessRepository repo, Locks locks) {
    this.repo = repo;
    this.locks = locks;
  }

  /**
   * 用途：检查本人、部门处理员或管理员是否可查看工单。
   * 输入：actor：用户；t：工单实体。
   * 输出：布尔值。
   */
  boolean visible(UserEntity actor, TicketEntity t) {
    return actor.role == Role.ADMIN
        || t.creatorId.equals(actor.id)
        || (actor.role == Role.AGENT && t.departmentId.equals(actor.departmentId));
  }

  /**
   * 用途：查找工单并校验访问权限。
   * 输入：actor：用户；id：工单 ID。
   * 输出：工单实体；不存在或越权时抛异常。
   */
  TicketEntity require(UserEntity actor, String id) {
    TicketEntity t = repo.find(TicketEntity.class, id);
    if (t == null) throw missing();
    if (!visible(actor, t)) throw forbidden();
    return t;
  }

  /**
   * 用途：按权限列出可见工单。
   * 输入：actor：用户。
   * 输出：按更新时间排序的工单 DTO 列表。
   */
  public List<Ticket> list(UserEntity actor) {
    return repo.query(TicketEntity.class, "order by e.updatedAt desc").stream()
        .filter(t -> visible(actor, t))
        .map(this::view)
        .toList();
  }

  /**
   * 用途：补充创建人与处理人姓名并生成 DTO。
   * 输入：t：工单实体。
   * 输出：工单 DTO。
   */
  Ticket view(TicketEntity t) {
    UserEntity creator = repo.find(UserEntity.class, t.creatorId),
        assignee = repo.find(UserEntity.class, t.assigneeId);
    return new Ticket(
        t.id,
        t.number,
        t.title,
        t.description,
        t.priority,
        t.status,
        t.departmentId,
        t.creatorId,
        creator.displayName,
        t.assigneeId,
        assignee == null ? null : assignee.displayName,
        t.resolution,
        t.rating,
        t.createdAt,
        t.updatedAt);
  }

  /**
   * 用途：在事务内按幂等键创建工单。
   * 输入：actor：创建人；request：标题、描述、优先级、幂等键。
   * 输出：工单 DTO；重复请求复用已创建记录。
   */
  public Ticket create(UserEntity actor, NewTicket request) {
    synchronized (locks.forKey("ticket:" + actor.id)) {
      return repo.tx(() -> view(createInTransaction(actor, request)));
    }
  }

  /**
   * 用途：锁住创建人并执行幂等校验及插入。
   * 输入：actor：创建人；request：工单内容及幂等键。
   * 输出：原有或新建工单实体；须由事务调用。
   */
  TicketEntity createInTransaction(UserEntity actor, NewTicket request) {
    // 幂等键标识同一次创建意图：网络重试返回原工单，同一键搭配不同内容则拒绝。
    // 创建人行锁把“查询是否存在 + 插入”串行化，避免两个并发请求都判断为首次创建。
    // Lock the creator to serialize the database idempotency key even across concurrent requests.
    repo.lock(UserEntity.class, actor.id);
    List<TicketEntity> previous =
        repo.query(
            TicketEntity.class,
            "where e.creatorId=?1 and e.idempotencyKey=?2",
            actor.id,
            request.idempotencyKey());
    if (!previous.isEmpty()) {
      TicketEntity t = previous.getFirst();
      if (!t.title.equals(request.title().trim())
          || !t.description.equals(request.description().trim())
          || t.priority != request.priority()) throw conflict("幂等键已用于不同工单内容");
      return t;
    }
    TicketEntity t = new TicketEntity();
    t.creatorId = actor.id;
    // 归属部门从登录用户推导，不能由请求或模型指定，以免跨部门创建工单。
    t.departmentId = actor.departmentId;
    t.title = request.title().trim();
    t.description = request.description().trim();
    t.priority = request.priority();
    t.idempotencyKey = request.idempotencyKey();
    return repo.save(t);
  }

  /**
   * 用途：取得工单及按时间排序的评论。
   * 输入：actor：用户；id：工单 ID。
   * 输出：含评论列表的工单详情。
   */
  public TicketDetail detail(UserEntity actor, String id) {
    Ticket t = view(require(actor, id));
    return new TicketDetail(
        t.id(),
        t.number(),
        t.title(),
        t.description(),
        t.priority(),
        t.status(),
        t.departmentId(),
        t.creatorId(),
        t.creatorName(),
        t.assigneeId(),
        t.assigneeName(),
        t.resolution(),
        t.rating(),
        t.createdAt(),
        t.updatedAt(),
        repo.query(CommentEntity.class, "where e.ticketId=?1 order by e.createdAt", id).stream()
            .map(this::commentView)
            .toList());
  }

  /**
   * 用途：校验角色和状态机后分配或推进工单。
   * 输入：actor：用户；id：工单 ID；patch：状态、处理人、解决说明或评分。
   * 输出：更新后的工单 DTO；事务写入变更。
   */
  public Ticket patch(UserEntity actor, String id, TicketPatch patch) {
    // 状态机由服务端控制：OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED。
    // 处理员负责推进解决，创建人或管理员评价关闭；行锁保护并发状态变更。
    return repo.tx(
        () -> {
          TicketEntity t = repo.lock(TicketEntity.class, id);
          if (t == null) throw missing();
          if (!visible(actor, t)) throw forbidden();
          boolean staff =
              actor.role == Role.ADMIN
                  || (actor.role == Role.AGENT && actor.departmentId.equals(t.departmentId));
          if (patch.status() == null && patch.assigneeId() == null) throw bad("请选择状态或处理人");
          if (patch.assigneeId() != null) {
            if (!staff) throw forbidden();
            if (t.status == Status.CLOSED) throw conflict("已关闭工单不能重新分配");
            UserEntity target = repo.find(UserEntity.class, patch.assigneeId());
            if (target == null
                || target.role == Role.EMPLOYEE
                || !target.departmentId.equals(t.departmentId)) throw bad("处理人必须是工单部门的服务专员或管理员");
            t.assigneeId = target.id;
          }
          if (patch.status() != null) {
            Status next = patch.status();
            if (next == Status.CLOSED) {
              if (!actor.id.equals(t.creatorId) && actor.role != Role.ADMIN) throw forbidden();
              if (t.status != Status.RESOLVED) throw conflict("只有已解决的工单可以关闭");
              if (patch.rating() == null) throw bad("关闭工单需要 1–5 分评价");
              t.rating = patch.rating();
            } else {
              if (!staff) throw forbidden();
              if (!((t.status == Status.OPEN && next == Status.IN_PROGRESS)
                  || (t.status == Status.IN_PROGRESS && next == Status.RESOLVED)))
                throw conflict("工单状态转换无效");
              if (next == Status.RESOLVED) {
                if (patch.resolution() == null || patch.resolution().isBlank())
                  throw bad("解决工单需要填写解决说明");
                t.resolution = patch.resolution().trim();
              }
            }
            t.status = next;
          }
          if (patch.rating() != null && patch.status() != Status.CLOSED) throw bad("评分仅在关闭工单时填写");
          if (patch.resolution() != null && patch.status() != Status.RESOLVED)
            throw bad("解决说明仅在解决工单时填写");
          t.updatedAt = Instant.now();
          return view(repo.save(t));
        });
  }

  /**
   * 用途：将评论实体转换为带作者姓名的 DTO。
   * 输入：c：评论实体。
   * 输出：评论 DTO。
   */
  Comment commentView(CommentEntity c) {
    return new Comment(
        c.id, repo.find(UserEntity.class, c.authorId).displayName, c.content, c.createdAt);
  }

  /**
   * 用途：在有权访问的工单下添加评论。
   * 输入：actor：作者；id：工单 ID；input：评论正文。
   * 输出：新评论 DTO；写入数据库。
   */
  public Comment comment(UserEntity actor, String id, NewComment input) {
    return repo.tx(
        () -> {
          TicketEntity t = require(actor, id);
          CommentEntity c = new CommentEntity();
          c.ticketId = t.id;
          c.authorId = actor.id;
          c.content = input.content().trim();
          return commentView(repo.save(c));
        });
  }
}

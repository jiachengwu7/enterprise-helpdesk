package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TicketService {
  final BusinessRepository repo;
  final Locks locks;

  TicketService(BusinessRepository repo, Locks locks) {
    this.repo = repo;
    this.locks = locks;
  }

  boolean visible(UserEntity actor, TicketEntity t) {
    return actor.role == Role.ADMIN
        || t.creatorId.equals(actor.id)
        || (actor.role == Role.AGENT && t.departmentId.equals(actor.departmentId));
  }

  TicketEntity require(UserEntity actor, String id) {
    TicketEntity t = repo.find(TicketEntity.class, id);
    if (t == null) throw missing();
    if (!visible(actor, t)) throw forbidden();
    return t;
  }

  public List<Ticket> list(UserEntity actor) {
    return repo.query(TicketEntity.class, "order by e.updatedAt desc").stream()
        .filter(t -> visible(actor, t))
        .map(this::view)
        .toList();
  }

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

  public Ticket create(UserEntity actor, NewTicket request) {
    synchronized (locks.forKey("ticket:" + actor.id)) {
      return repo.tx(() -> view(createInTransaction(actor, request)));
    }
  }

  TicketEntity createInTransaction(UserEntity actor, NewTicket request) {
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
    t.departmentId = actor.departmentId;
    t.title = request.title().trim();
    t.description = request.description().trim();
    t.priority = request.priority();
    t.idempotencyKey = request.idempotencyKey();
    return repo.save(t);
  }

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

  public Ticket patch(UserEntity actor, String id, TicketPatch patch) {
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

  Comment commentView(CommentEntity c) {
    return new Comment(
        c.id, repo.find(UserEntity.class, c.authorId).displayName, c.content, c.createdAt);
  }

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

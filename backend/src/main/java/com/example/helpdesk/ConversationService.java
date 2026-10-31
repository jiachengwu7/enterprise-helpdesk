package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
// 编排 Java 业务与 Python AI 流程：先决定可访问的数据，再请求回答，确认后才创建业务工单。
public class ConversationService {
  final BusinessRepository repo;
  final AiClient ai;
  final DocumentService docs;
  final TicketService tickets;
  final ObjectMapper json;
  final Locks locks;

  /**
   * 用途：注入会话编排所需的服务和序列化工具。
   * 输入：repo：仓储；ai：AI 客户端；docs、tickets：业务服务；json：序列化器；locks：进程锁。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  ConversationService(
      BusinessRepository repo,
      AiClient ai,
      DocumentService docs,
      TicketService tickets,
      ObjectMapper json,
      Locks locks) {
    this.repo = repo;
    this.ai = ai;
    this.docs = docs;
    this.tickets = tickets;
    this.json = json;
    this.locks = locks;
  }

  /**
   * 用途：校验会话存在且属于当前用户。
   * 输入：actor：当前用户；id：会话 ID。
   * 输出：会话实体；不存在或越权时抛业务异常。
   */
  ConversationEntity require(UserEntity actor, String id) {
    // 会话严格归创建者所有，管理员角色也不能绕过这个归属检查。
    ConversationEntity c = repo.find(ConversationEntity.class, id);
    if (c == null) throw missing();
    if (!c.ownerId.equals(actor.id)) throw forbidden();
    return c;
  }

  /**
   * 用途：将会话实体转换为列表 DTO。
   * 输入：c：会话实体。
   * 输出：会话 ID、标题和时间。
   */
  Conversation view(ConversationEntity c) {
    return new Conversation(c.id, c.title, c.createdAt, c.updatedAt);
  }

  /**
   * 用途：反序列化数据库中的工单草稿。
   * 输入：value：可空 JSON。
   * 输出：Proposal；输入 null 时返回 null。
   */
  Proposal proposal(String value) {
    return value == null ? null : json.readValue(value, Proposal.class);
  }

  /**
   * 用途：按更新时间列出本人会话。
   * 输入：actor：当前用户。
   * 输出：会话 DTO 列表。
   */
  public List<Conversation> list(UserEntity actor) {
    return repo
        .query(ConversationEntity.class, "where e.ownerId=?1 order by e.updatedAt desc", actor.id)
        .stream()
        .map(this::view)
        .toList();
  }

  /**
   * 用途：创建归属于当前用户的新会话。
   * 输入：actor：当前用户；input：可选标题。
   * 输出：会话 DTO；写入会话记录。
   */
  public Conversation create(UserEntity actor, NewConversation input) {
    ConversationEntity c = new ConversationEntity();
    c.ownerId = actor.id;
    c.title = input.title() == null || input.title().isBlank() ? "新会话" : input.title().trim();
    return view(repo.save(c));
  }

  /**
   * 用途：在会话锁内读取安全的会话详情。
   * 输入：actor：当前用户；id：会话 ID。
   * 输出：会话详情，包含消息及待确认草稿。
   */
  public ConversationDetail detail(UserEntity actor, String id) {
    synchronized (locks.forKey("chat:" + id)) {
      return detailLocked(actor, id);
    }
  }

  /**
   * 用途：尝试恢复挂起草稿并重新校验历史来源。
   * 输入：actor：当前用户；id：会话 ID。
   * 输出：经权限和版本过滤的会话详情；可能补写恢复记录。
   */
  private ConversationDetail detailLocked(UserEntity actor, String id) {
    ConversationEntity c = require(actor, id);
    try {
      c = recoverPending(actor, c);
    } catch (Failure ignored) {
      // Existing history remains available when the optional AI dependency is down.
    }
    List<Message> messages =
        repo
            .query(MessageEntity.class, "where e.conversationId=?1 order by e.createdAt", id)
            .stream()
            .map(m -> historyMessage(actor, m))
            .toList();
    Proposal pending = proposal(c.pendingJson);
    if (pending != null) {
      String pendingId = pending.id();
      pending =
          messages.stream()
              .map(Message::proposal)
              .filter(p -> p != null && p.id().equals(pendingId))
              .findFirst()
              .orElse(pending);
    }
    return new ConversationDetail(c.id, c.title, messages, pending);
  }

  private static final String REDACTED = "原回答引用的文档权限或版本已变化，内容已隐藏。请重新提问。";

  /**
   * 用途：屏蔽引用失效的历史回答。
   * 输入：actor：当前用户；message：消息实体。
   * 输出：安全消息 DTO；失效时隐藏整段回答。
   */
  private Message historyMessage(UserEntity actor, MessageEntity message) {
    // 旧回答可能包含原文信息：引用失效时隐藏整段回答，仅移除引用链接不足以保护内容。
    List<Source> original = Arrays.asList(json.readValue(message.sourcesJson, Source[].class));
    List<Source> allowed = docs.filterSources(actor, original);
    Proposal draft = proposal(message.proposalJson);
    boolean redacted = message.role.equals("assistant") && allowed.size() != original.size();
    if (redacted && draft != null) {
      draft = new Proposal(draft.id(), "资料已变化，请拒绝此建议后重新提问", REDACTED, draft.priority());
    }
    return new Message(
        message.id,
        message.role,
        redacted ? REDACTED : message.content,
        redacted ? List.of() : allowed,
        draft,
        message.createdAt);
  }

  public record PendingState(String question, Answer pending) {}

  /**
   * 用途：从 Python 检查点恢复丢失的待确认响应。
   * 输入：actor：当前用户；conversation：会话实体。
   * 输出：更新后的会话；按需补写历史与草稿，不调用模型。
   */
  private ConversationEntity recoverPending(UserEntity actor, ConversationEntity conversation) {
    // Python 已保存待确认状态但 HTTP 响应丢失时，从检查点恢复草稿；这里不会再次调用模型。
    if (conversation.pendingJson != null) return conversation;
    PendingState state =
        ai.post(
            "/internal/chat/state",
            Map.of("conversationId", conversation.id, "userId", actor.id),
            PendingState.class);
    if (state == null || state.pending() == null) return conversation;
    Answer answer = state.pending();
    Proposal draft = answer.proposal();
    if (draft == null
        || state.question() == null
        || state.question().isBlank()
        || state.question().length() > 4000
        || answer.answer() == null
        || answer.answer().isBlank()
        || !Set.of("llm", "retrieval-only").contains(answer.mode())) {
      throw unavailable();
    }
    validateProposal(draft);
    List<Source> original = answer.sources() == null ? List.of() : answer.sources();
    List<Source> current = docs.filterSources(actor, original);
    boolean stale = current.size() != original.size();
    String content = stale ? REDACTED : answer.answer();
    List<Source> safeSources = stale ? List.of() : current;
    return repo.tx(
        () -> {
          ConversationEntity locked = repo.lock(ConversationEntity.class, conversation.id);
          if (locked == null) throw missing();
          if (!locked.ownerId.equals(actor.id)) throw forbidden();
          if (locked.pendingJson != null) return locked;
          DecisionEntity decision = repo.find(DecisionEntity.class, draft.id());
          if (decision != null && decision.resumed) return locked;
          boolean persisted =
              repo
                  .query(
                      MessageEntity.class,
                      "where e.conversationId=?1 and e.proposalJson is not null",
                      conversation.id)
                  .stream()
                  .map(m -> proposal(m.proposalJson))
                  .anyMatch(p -> p.id().equals(draft.id()));
          if (!persisted) {
            persistMessage(conversation.id, "user", state.question(), List.of(), null);
            persistMessage(conversation.id, "assistant", content, safeSources, draft);
          }
          locked.pendingJson = json.writeValueAsString(draft);
          locked.updatedAt = Instant.now();
          if (locked.title.equals("新会话"))
            locked.title = state.question().substring(0, Math.min(60, state.question().length()));
          return repo.save(locked);
        });
  }

  /**
   * 用途：检查外部草稿结构和字段长度。
   * 输入：proposal：AI 服务返回的草稿。
   * 输出：void；非法时抛依赖不可用异常。
   */
  private void validateProposal(Proposal proposal) {
    try {
      UUID.fromString(proposal.id());
    } catch (Exception ex) {
      throw unavailable();
    }
    if (proposal.title() == null
        || proposal.title().isBlank()
        || proposal.title().length() > 200
        || proposal.description() == null
        || proposal.description().isBlank()
        || proposal.description().length() > 10000
        || proposal.priority() == null) throw unavailable();
  }

  /**
   * 用途：计算授权范围、调用 AI 并保存本轮问答。
   * 输入：actor、id：用户及会话；input：问题和草稿开关。
   * 输出：Answer；校验来源后写入历史与待确认草稿。
   */
  public Answer message(UserEntity actor, String id, Question input) {
    synchronized (locks.forKey("chat:" + id)) {
      ConversationEntity c = require(actor, id);
      c = recoverPending(actor, c);
      if (c.pendingJson != null) throw conflict("请先确认或拒绝待处理的工单建议");
      List<String> allowed = docs.allowedIds(actor);
      // 身份和授权集合由服务端生成，前端与模型都不能自行扩大检索范围。
      Answer response =
          ai.post(
              "/internal/chat",
              Map.of(
                  "conversationId",
                  id,
                  "userId",
                  actor.id,
                  "departmentId",
                  actor.departmentId,
                  "role",
                  actor.role,
                  "allowedDocumentIds",
                  allowed,
                  "question",
                  input.question().trim(),
                  "requestTicket",
                  input.requestTicket()),
              Answer.class);
      if (response.answer() == null
          || response.answer().isBlank()
          || !Set.of("llm", "retrieval-only").contains(response.mode())) throw unavailable();
      Proposal p = response.proposal();
      if (p != null) {
        try {
          UUID.fromString(p.id());
        } catch (Exception ex) {
          throw unavailable();
        }
        if (p.title() == null
            || p.title().isBlank()
            || p.title().length() > 200
            || p.description() == null
            || p.description().isBlank()
            || p.description().length() > 10000
            || p.priority() == null) throw unavailable();
      }
      List<Source> sources =
          docs.filterSources(actor, response.sources()).stream()
              .filter(s -> allowed.contains(s.documentId()))
              .toList();
      if (response.sources() != null && sources.size() != response.sources().size()) {
        // Removing citations alone is insufficient: generated prose may quote the rejected
        // evidence.
        // Discard the whole answer and any unconfirmed graph proposal, then allow a fresh request.
        try {
          ai.delete(
              "/internal/conversations/"
                  + id
                  + "?userId="
                  + URLEncoder.encode(actor.id, StandardCharsets.UTF_8));
        } catch (Failure ignored) {
          /* No business write occurred; user can retry the request. */
        }
        throw conflict("文档权限或版本已变化，请重新提问");
      }
      Answer result =
          new Answer(
              response.answer(),
              sources,
              p,
              response.mode(),
              response.trace() == null ? List.of() : response.trace());
      return repo.tx(
          () -> {
            ConversationEntity current = require(actor, id);
            persistMessage(id, "user", input.question().trim(), List.of(), null);
            persistMessage(id, "assistant", result.answer(), result.sources(), result.proposal());
            current.pendingJson = p == null ? null : json.writeValueAsString(p);
            current.updatedAt = Instant.now();
            if (current.title.equals("新会话"))
              current.title =
                  input
                      .question()
                      .trim()
                      .substring(0, Math.min(60, input.question().trim().length()));
            repo.save(current);
            return result;
          });
    }
  }

  /**
   * 用途：保存一条会话消息。
   * 输入：conversationId、role、content：归属及正文；sources：证据；p：可空草稿。
   * 输出：void；写入消息记录。
   */
  void persistMessage(
      String conversationId, String role, String content, List<Source> sources, Proposal p) {
    MessageEntity m = new MessageEntity();
    m.conversationId = conversationId;
    m.role = role;
    m.content = content;
    m.sourcesJson = json.writeValueAsString(sources);
    m.proposalJson = p == null ? null : json.writeValueAsString(p);
    repo.save(m);
  }

  /**
   * 用途：事务保存人工决定和工单，再恢复 Python 流程。
   * 输入：actor、id：用户及会话；input：草稿 ID 和决定。
   * 输出：确认结果及可空工单；重复确认复用原结果。
   */
  public ConfirmationResult confirm(UserEntity actor, String id, Confirmation input) {
    // 先在同一数据库事务内保存人工决定和工单，再通知 Python 恢复图。
    // 这不是跨服务分布式事务；通知失败后的重试通过 DecisionEntity 复用已创建工单。
    synchronized (locks.forKey("chat:" + id)) {
      require(actor, id);
      // The ticket and approval record commit together before the external graph resumes.
      DecisionEntity decision =
          repo.tx(
              () -> {
                ConversationEntity c = repo.lock(ConversationEntity.class, id);
                if (c == null) throw missing();
                if (!c.ownerId.equals(actor.id)) throw forbidden();
                DecisionEntity existing = repo.find(DecisionEntity.class, input.proposalId());
                if (existing != null) {
                  if (!existing.conversationId.equals(id) || existing.approved != input.approved())
                    throw conflict("确认结果与原决定不一致");
                  return existing;
                }
                Proposal p = proposal(c.pendingJson);
                if (p == null || !p.id().equals(input.proposalId())) throw conflict("工单建议不存在或已失效");
                DecisionEntity d = new DecisionEntity();
                d.id = p.id();
                d.conversationId = id;
                d.approved = input.approved();
                if (d.approved)
                  d.ticketId =
                      tickets.createInTransaction(
                              actor,
                              new NewTicket(
                                  p.title(), p.description(), p.priority(), "proposal:" + p.id()))
                          .id;
                return repo.save(d);
              });
      Ticket ticket =
          decision.ticketId == null
              ? null
              : tickets.view(repo.find(TicketEntity.class, decision.ticketId));
      if (decision.resumed) return new ConfirmationResult(decision.answer, ticket);
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("conversationId", id);
      body.put("userId", actor.id);
      body.put("proposalId", decision.id);
      body.put("approved", decision.approved);
      body.put("ticketId", ticket == null ? null : ticket.id());
      body.put("ticketNumber", ticket == null ? null : ticket.number());
      Resume response = ai.post("/internal/chat/confirm", body, Resume.class);
      if (response.answer() == null || response.answer().isBlank()) throw unavailable();
      return repo.tx(
          () -> {
            DecisionEntity d = repo.lock(DecisionEntity.class, decision.id);
            if (!d.resumed) {
              d.resumed = true;
              d.answer = response.answer();
              repo.save(d);
              ConversationEntity c = require(actor, id);
              c.pendingJson = null;
              c.updatedAt = Instant.now();
              repo.save(c);
              persistMessage(id, "assistant", d.answer, List.of(), null);
            }
            return new ConfirmationResult(d.answer, ticket);
          });
    }
  }

  public record Resume(String answer) {}

  /**
   * 用途：清理本人会话及关联的 AI 状态。
   * 输入：actor：当前用户；id：会话 ID。
   * 输出：void；删除检查点、消息、决定和会话记录。
   */
  public void delete(UserEntity actor, String id) {
    synchronized (locks.forKey("chat:" + id)) {
      require(actor, id);
      ai.delete(
          "/internal/conversations/"
              + id
              + "?userId="
              + URLEncoder.encode(actor.id, StandardCharsets.UTF_8));
      repo.tx(
          () -> {
            repo.delete("MessageEntity", "where e.conversationId=?1", id);
            repo.delete("DecisionEntity", "where e.conversationId=?1", id);
            repo.remove(require(actor, id));
            return null;
          });
    }
  }
}

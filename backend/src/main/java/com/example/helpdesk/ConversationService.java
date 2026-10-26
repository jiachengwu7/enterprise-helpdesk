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
public class ConversationService {
  final BusinessRepository repo;
  final AiClient ai;
  final DocumentService docs;
  final TicketService tickets;
  final ObjectMapper json;
  final Locks locks;

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

  ConversationEntity require(UserEntity actor, String id) {
    ConversationEntity c = repo.find(ConversationEntity.class, id);
    if (c == null) throw missing();
    if (!c.ownerId.equals(actor.id)) throw forbidden();
    return c;
  }

  Conversation view(ConversationEntity c) {
    return new Conversation(c.id, c.title, c.createdAt, c.updatedAt);
  }

  Proposal proposal(String value) {
    return value == null ? null : json.readValue(value, Proposal.class);
  }

  public List<Conversation> list(UserEntity actor) {
    return repo
        .query(ConversationEntity.class, "where e.ownerId=?1 order by e.updatedAt desc", actor.id)
        .stream()
        .map(this::view)
        .toList();
  }

  public Conversation create(UserEntity actor, NewConversation input) {
    ConversationEntity c = new ConversationEntity();
    c.ownerId = actor.id;
    c.title = input.title() == null || input.title().isBlank() ? "新会话" : input.title().trim();
    return view(repo.save(c));
  }

  public ConversationDetail detail(UserEntity actor, String id) {
    synchronized (locks.forKey("chat:" + id)) {
      return detailLocked(actor, id);
    }
  }

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

  private Message historyMessage(UserEntity actor, MessageEntity message) {
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

  private ConversationEntity recoverPending(UserEntity actor, ConversationEntity conversation) {
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

  public Answer message(UserEntity actor, String id, Question input) {
    synchronized (locks.forKey("chat:" + id)) {
      ConversationEntity c = require(actor, id);
      c = recoverPending(actor, c);
      if (c.pendingJson != null) throw conflict("请先确认或拒绝待处理的工单建议");
      List<String> allowed = docs.allowedIds(actor);
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

  public ConfirmationResult confirm(UserEntity actor, String id, Confirmation input) {
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

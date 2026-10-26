package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final AuthService auth;
  private final TicketService tickets;
  private final DocumentService documents;
  private final ConversationService conversations;
  private final AiClient ai;
  private final BusinessRepository repo;

  ApiController(
      AuthService auth,
      TicketService tickets,
      DocumentService documents,
      ConversationService conversations,
      AiClient ai,
      BusinessRepository repo) {
    this.auth = auth;
    this.tickets = tickets;
    this.documents = documents;
    this.conversations = conversations;
    this.ai = ai;
    this.repo = repo;
  }

  @GetMapping("/health")
  Envelope<?> health() {
    return Envelope.ok(Map.of("status", "ok"));
  }

  @PostMapping("/auth/login")
  Envelope<LoginResult> login(@Valid @RequestBody Login body) {
    return Envelope.ok(auth.login(body));
  }

  @GetMapping("/auth/me")
  Envelope<User> me(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(AuthService.view(actor));
  }

  @PostMapping("/auth/logout")
  Envelope<Void> logout(@RequestHeader("Authorization") String header) {
    auth.logout(header.substring(7));
    return Envelope.ok(null);
  }

  @GetMapping("/departments")
  Envelope<List<Department>> departments() {
    return Envelope.ok(List.of(new Department("IT", "信息技术部"), new Department("HR", "人力资源部")));
  }

  @GetMapping("/users")
  Envelope<List<User>> users(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(auth.users(actor));
  }

  @PostMapping("/users")
  Envelope<User> user(@AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewUser body) {
    return Envelope.ok(auth.create(actor, body));
  }

  @GetMapping("/dashboard")
  Envelope<Dashboard> dashboard(@AuthenticationPrincipal UserEntity actor) {
    List<Ticket> all = tickets.list(actor);
    return Envelope.ok(
        new Dashboard(
            documents.accessible(actor).size(),
            all.size(),
            all.stream()
                .filter(t -> t.status() == Status.OPEN || t.status() == Status.IN_PROGRESS)
                .count(),
            all.stream()
                .filter(t -> t.status() == Status.RESOLVED || t.status() == Status.CLOSED)
                .count(),
            conversations.list(actor).size()));
  }

  @GetMapping("/system/status")
  Envelope<?> system() {
    return Envelope.ok(
        Map.of(
            "ai",
            ai.health(),
            "database",
            repo.databaseReady() ? "ok" : "unavailable",
            "version",
            "1.0.0"));
  }

  @GetMapping("/documents")
  Envelope<List<Document>> documents(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(documents.list(actor));
  }

  @PostMapping("/documents")
  Envelope<Document> upload(
      @AuthenticationPrincipal UserEntity actor,
      @RequestParam MultipartFile file,
      @RequestParam String title,
      @RequestParam String departmentId,
      @RequestParam Visibility visibility) {
    return Envelope.ok(documents.upload(actor, null, file, title, departmentId, visibility));
  }

  @PutMapping("/documents/{id}")
  Envelope<Document> replace(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @RequestParam MultipartFile file,
      @RequestParam String title,
      @RequestParam String departmentId,
      @RequestParam Visibility visibility) {
    return Envelope.ok(documents.upload(actor, id, file, title, departmentId, visibility));
  }

  @DeleteMapping("/documents/{id}")
  Envelope<Void> deleteDocument(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    documents.delete(actor, id);
    return Envelope.ok(null);
  }

  @GetMapping("/documents/{id}/download")
  ResponseEntity<byte[]> download(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    var file = documents.download(actor, id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(file.content());
  }

  @GetMapping("/conversations")
  Envelope<List<Conversation>> conversations(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(conversations.list(actor));
  }

  @PostMapping("/conversations")
  Envelope<Conversation> conversation(
      @AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewConversation body) {
    return Envelope.ok(conversations.create(actor, body));
  }

  @GetMapping("/conversations/{id}")
  Envelope<ConversationDetail> conversation(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    return Envelope.ok(conversations.detail(actor, id));
  }

  @DeleteMapping("/conversations/{id}")
  Envelope<Void> deleteConversation(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    conversations.delete(actor, id);
    return Envelope.ok(null);
  }

  @PostMapping("/conversations/{id}/messages")
  Envelope<Answer> message(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody Question body) {
    return Envelope.ok(conversations.message(actor, id, body));
  }

  @PostMapping("/conversations/{id}/confirm")
  Envelope<ConfirmationResult> confirm(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody Confirmation body) {
    return Envelope.ok(conversations.confirm(actor, id, body));
  }

  @GetMapping("/tickets")
  Envelope<List<Ticket>> tickets(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(tickets.list(actor));
  }

  @PostMapping("/tickets")
  Envelope<Ticket> ticket(
      @AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewTicket body) {
    return Envelope.ok(tickets.create(actor, body));
  }

  @GetMapping("/tickets/{id}")
  Envelope<TicketDetail> ticket(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    return Envelope.ok(tickets.detail(actor, id));
  }

  @PatchMapping("/tickets/{id}")
  Envelope<Ticket> patchTicket(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody TicketPatch body) {
    return Envelope.ok(tickets.patch(actor, id, body));
  }

  @PostMapping("/tickets/{id}/comments")
  Envelope<Comment> comment(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody NewComment body) {
    return Envelope.ok(tickets.comment(actor, id, body));
  }
}

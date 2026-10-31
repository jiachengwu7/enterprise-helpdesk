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

  /**
   * 用途：注入 API 路由所需的业务服务。
   * 输入：auth、tickets、documents、conversations：业务服务；ai：AI 客户端；repo：仓储。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
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

  /**
   * 用途：提供业务服务存活检查。
   * 输入：无参数。
   * 输出：健康状态的统一响应。
   */
  @GetMapping("/health")
  Envelope<?> health() {
    return Envelope.ok(Map.of("status", "ok"));
  }

  /**
   * 用途：接收登录表单并调用认证服务。
   * 输入：body：经过校验的账号密码。
   * 输出：包含令牌和用户信息的统一响应；创建会话。
   */
  @PostMapping("/auth/login")
  Envelope<LoginResult> login(@Valid @RequestBody Login body) {
    return Envelope.ok(auth.login(body));
  }

  /**
   * 用途：返回当前已认证用户信息。
   * 输入：actor：安全上下文中的用户。
   * 输出：用户 DTO 的统一响应。
   */
  @GetMapping("/auth/me")
  Envelope<User> me(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(AuthService.view(actor));
  }

  /**
   * 用途：撤销当前 Bearer 登录令牌。
   * 输入：header：Authorization 请求头。
   * 输出：空成功响应；删除登录会话。
   */
  @PostMapping("/auth/logout")
  Envelope<Void> logout(@RequestHeader("Authorization") String header) {
    auth.logout(header.substring(7));
    return Envelope.ok(null);
  }

  /**
   * 用途：返回系统支持的部门选项。
   * 输入：无参数。
   * 输出：部门列表的统一响应。
   */
  @GetMapping("/departments")
  Envelope<List<Department>> departments() {
    return Envelope.ok(List.of(new Department("IT", "信息技术部"), new Department("HR", "人力资源部")));
  }

  /**
   * 用途：查询当前角色有权查看的用户。
   * 输入：actor：当前用户。
   * 输出：用户列表的统一响应。
   */
  @GetMapping("/users")
  Envelope<List<User>> users(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(auth.users(actor));
  }

  /**
   * 用途：创建新用户。
   * 输入：actor：当前管理员；body：新用户表单。
   * 输出：用户 DTO 的统一响应；写入用户记录。
   */
  @PostMapping("/users")
  Envelope<User> user(@AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewUser body) {
    return Envelope.ok(auth.create(actor, body));
  }

  /**
   * 用途：统计当前用户可见的业务数据。
   * 输入：actor：当前用户。
   * 输出：文档、工单和会话数量的统一响应。
   */
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

  /**
   * 用途：汇总数据库及 AI 依赖状态。
   * 输入：无参数。
   * 输出：系统状态统一响应，不暴露密钥。
   */
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

  /**
   * 用途：查询文档列表和索引状态。
   * 输入：actor：当前用户。
   * 输出：可见文档列表的统一响应。
   */
  @GetMapping("/documents")
  Envelope<List<Document>> documents(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(documents.list(actor));
  }

  /**
   * 用途：接收新文档上传。
   * 输入：actor：用户；file、title、departmentId、visibility：文件与元数据。
   * 输出：文档 DTO 的统一响应；提交异步索引。
   */
  @PostMapping("/documents")
  Envelope<Document> upload(
      @AuthenticationPrincipal UserEntity actor,
      @RequestParam MultipartFile file,
      @RequestParam String title,
      @RequestParam String departmentId,
      @RequestParam Visibility visibility) {
    return Envelope.ok(documents.upload(actor, null, file, title, departmentId, visibility));
  }

  /**
   * 用途：提交指定文档的新版本。
   * 输入：actor、id：用户与文档；file、title、departmentId、visibility：新文件及元数据。
   * 输出：新版本文档 DTO 的统一响应。
   */
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

  /**
   * 用途：删除文档并安排索引清理。
   * 输入：actor：用户；id：文档 ID。
   * 输出：空成功响应；文档立即不可见。
   */
  @DeleteMapping("/documents/{id}")
  Envelope<Void> deleteDocument(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    documents.delete(actor, id);
    return Envelope.ok(null);
  }

  /**
   * 用途：在权限校验后向浏览器返回原件。
   * 输入：actor：用户；id：文档 ID。
   * 输出：带下载文件名的字节响应，不使用 JSON 包裹。
   */
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

  /**
   * 用途：列出本人会话。
   * 输入：actor：用户。
   * 输出：会话列表统一响应。
   */
  @GetMapping("/conversations")
  Envelope<List<Conversation>> conversations(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(conversations.list(actor));
  }

  /**
   * 用途：创建会话或读取指定会话详情，取决于重载参数。
   * 输入：actor：用户；body：新会话输入，或 id：现有会话 ID。
   * 输出：新会话或会话详情的统一响应。
   */
  @PostMapping("/conversations")
  Envelope<Conversation> conversation(
      @AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewConversation body) {
    return Envelope.ok(conversations.create(actor, body));
  }

  /**
   * 用途：创建会话或读取指定会话详情，取决于重载参数。
   * 输入：actor：用户；body：新会话输入，或 id：现有会话 ID。
   * 输出：新会话或会话详情的统一响应。
   */
  @GetMapping("/conversations/{id}")
  Envelope<ConversationDetail> conversation(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    return Envelope.ok(conversations.detail(actor, id));
  }

  /**
   * 用途：删除本人会话。
   * 输入：actor：用户；id：会话 ID。
   * 输出：空成功响应；清理业务历史和 AI 状态。
   */
  @DeleteMapping("/conversations/{id}")
  Envelope<Void> deleteConversation(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    conversations.delete(actor, id);
    return Envelope.ok(null);
  }

  /**
   * 用途：提交问题并返回 AI 流程结果。
   * 输入：actor、id：用户和会话；body：问题与草稿开关。
   * 输出：Answer 统一响应；保存问答历史。
   */
  @PostMapping("/conversations/{id}/messages")
  Envelope<Answer> message(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody Question body) {
    return Envelope.ok(conversations.message(actor, id, body));
  }

  /**
   * 用途：确认或拒绝工单草稿。
   * 输入：actor、id：用户和会话；body：草稿 ID 与决定。
   * 输出：确认结果统一响应；批准时创建工单。
   */
  @PostMapping("/conversations/{id}/confirm")
  Envelope<ConfirmationResult> confirm(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody Confirmation body) {
    return Envelope.ok(conversations.confirm(actor, id, body));
  }

  /**
   * 用途：列出有权访问的工单。
   * 输入：actor：用户。
   * 输出：工单列表统一响应。
   */
  @GetMapping("/tickets")
  Envelope<List<Ticket>> tickets(@AuthenticationPrincipal UserEntity actor) {
    return Envelope.ok(tickets.list(actor));
  }

  /**
   * 用途：创建工单或读取详情，取决于重载参数。
   * 输入：actor：用户；body：新工单输入，或 id：现有工单 ID。
   * 输出：工单或工单详情的统一响应。
   */
  @PostMapping("/tickets")
  Envelope<Ticket> ticket(
      @AuthenticationPrincipal UserEntity actor, @Valid @RequestBody NewTicket body) {
    return Envelope.ok(tickets.create(actor, body));
  }

  /**
   * 用途：创建工单或读取详情，取决于重载参数。
   * 输入：actor：用户；body：新工单输入，或 id：现有工单 ID。
   * 输出：工单或工单详情的统一响应。
   */
  @GetMapping("/tickets/{id}")
  Envelope<TicketDetail> ticket(
      @AuthenticationPrincipal UserEntity actor, @PathVariable String id) {
    return Envelope.ok(tickets.detail(actor, id));
  }

  /**
   * 用途：分配处理人或推进工单状态。
   * 输入：actor、id：用户和工单；body：变更内容。
   * 输出：更新后的工单统一响应。
   */
  @PatchMapping("/tickets/{id}")
  Envelope<Ticket> patchTicket(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody TicketPatch body) {
    return Envelope.ok(tickets.patch(actor, id, body));
  }

  /**
   * 用途：提交工单评论。
   * 输入：actor、id：用户和工单；body：评论内容。
   * 输出：新评论的统一响应；写入数据库。
   */
  @PostMapping("/tickets/{id}/comments")
  Envelope<Comment> comment(
      @AuthenticationPrincipal UserEntity actor,
      @PathVariable String id,
      @Valid @RequestBody NewComment body) {
    return Envelope.ok(tickets.comment(actor, id, body));
  }
}

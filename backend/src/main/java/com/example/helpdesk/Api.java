package com.example.helpdesk;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Browser and internal boundary types; persistence entities never cross the API. */
public final class Api {
  /**
   * 用途：禁止外部实例化静态定义容器。
   * 输入：无参数。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  private Api() {}

  public enum Role {
    ADMIN,
    AGENT,
    EMPLOYEE
  }

  public enum Visibility {
    PUBLIC,
    DEPARTMENT
  }

  public enum Priority {
    LOW,
    MEDIUM,
    HIGH
  }

  public enum Status {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED
  }

  public record Envelope<T>(int code, String message, T data) {
    /**
     * 用途：包装成功的业务数据。
     * 输入：data：泛型业务结果。
     * 输出：code=200 的 Envelope。
     */
    static <T> Envelope<T> ok(T data) {
      return new Envelope<>(200, "success", data);
    }
  }

  public record User(
      String id, String username, String displayName, String departmentId, Role role) {}

  public record Login(
      @NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 200) String password) {}

  public record LoginResult(String token, User user) {}

  public record NewUser(
      @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,80}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 100) String displayName,
      @Pattern(regexp = "IT|HR") @NotNull String departmentId,
      @NotNull Role role) {}

  public record Department(String id, String name) {}

  public record Document(
      String id,
      String title,
      String filename,
      String departmentId,
      Visibility visibility,
      int version,
      String status,
      int chunkCount,
      String error,
      Instant createdAt,
      String indexMode) {}

  public record Source(
      String documentId,
      String title,
      String filename,
      int chunkIndex,
      String content,
      double score,
      int version) {}

  public record Proposal(String id, String title, String description, Priority priority) {}

  public record Answer(
      String answer, List<Source> sources, Proposal proposal, String mode, List<String> trace) {}

  public record Conversation(String id, String title, Instant createdAt, Instant updatedAt) {}

  public record ConversationDetail(
      String id, String title, List<Message> messages, Proposal pendingProposal) {}

  public record Message(
      String id,
      String role,
      String content,
      List<Source> sources,
      Proposal proposal,
      Instant createdAt) {}

  public record NewConversation(@Size(max = 200) String title) {}

  public record Question(@NotBlank @Size(max = 4000) String question, Boolean requestTicket) {
    /**
     * 用途：为缺省的工单草稿开关提供默认值。
     * 输入：question：问题；requestTicket：可空布尔值。
     * 输出：构造函数，无返回值；将 null 归一为 false。
     */
    public Question {
      requestTicket = Boolean.TRUE.equals(requestTicket);
    }
  }

  public record Confirmation(
      @NotBlank @Size(max = 36) String proposalId, @NotNull Boolean approved) {}

  public record ConfirmationResult(String answer, Ticket ticket) {}

  public record NewTicket(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 10000) String description,
      @NotNull Priority priority,
      @NotBlank @Size(max = 160) String idempotencyKey) {}

  public record TicketPatch(
      Status status,
      String assigneeId,
      @Size(max = 10000) String resolution,
      @Min(1) @Max(5) Integer rating) {}

  public record Ticket(
      String id,
      String number,
      String title,
      String description,
      Priority priority,
      Status status,
      String departmentId,
      String creatorId,
      String creatorName,
      String assigneeId,
      String assigneeName,
      String resolution,
      Integer rating,
      Instant createdAt,
      Instant updatedAt) {}

  public record TicketDetail(
      String id,
      String number,
      String title,
      String description,
      Priority priority,
      Status status,
      String departmentId,
      String creatorId,
      String creatorName,
      String assigneeId,
      String assigneeName,
      String resolution,
      Integer rating,
      Instant createdAt,
      Instant updatedAt,
      List<Comment> comments) {}

  public record NewComment(@NotBlank @Size(max = 4000) String content) {}

  public record Comment(String id, String authorName, String content, Instant createdAt) {}

  public record Dashboard(
      long documents, long tickets, long openTickets, long resolvedTickets, long conversations) {}

  public record IndexState(
      String id, String status, int chunkCount, String error, String indexMode) {}

  public record IndexStates(List<IndexState> documents) {}
}

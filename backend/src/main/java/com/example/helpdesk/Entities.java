package com.example.helpdesk;

import static com.example.helpdesk.Api.*;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

public final class Entities {
  /**
   * 用途：禁止外部实例化静态定义容器。
   * 输入：无参数。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  private Entities() {}

  /**
   * 用途：生成实体主键。
   * 输入：无参数。
   * 输出：随机 UUID 字符串。
   */
  static String id() {
    return UUID.randomUUID().toString();
  }

  @Entity(name = "UserEntity")
  @Table(name = "hd_users")
  public static class UserEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 80, unique = true)
    public String username;

    @Column(nullable = false, length = 100)
    public String displayName;

    @Column(nullable = false, length = 8)
    public String departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Role role;

    @Column(nullable = false, length = 100)
    public String passwordHash;
  }

  @Entity(name = "SessionEntity")
  @Table(name = "hd_sessions")
  public static class SessionEntity {
    @Id
    @Column(length = 64)
    public String id;

    @Column(nullable = false, length = 36)
    public String userId;

    @Column(nullable = false)
    public Instant expiresAt;
  }

  @Entity(name = "DocumentEntity")
  @Table(name = "hd_documents")
  public static class DocumentEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, length = 255)
    public String filename;

    @Column(nullable = false, length = 8)
    public String departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Visibility visibility;

    @Column(nullable = false)
    public int version = 1;

    @Column(nullable = false, length = 16)
    public String status = "QUEUED";

    @Column(nullable = false)
    public int chunkCount;

    @Column(length = 500)
    public String error;

    @Column(length = 32)
    public String indexMode;

    @Column(nullable = false)
    public boolean active = true;

    @Column(nullable = false)
    public boolean indexAccepted;

    @Column(nullable = false)
    public boolean cleanupDone;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
  }

  @Entity(name = "ConversationEntity")
  @Table(name = "hd_conversations")
  public static class ConversationEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 36)
    public String ownerId;

    @Column(nullable = false, length = 200)
    public String title;

    @Column(columnDefinition = "text")
    public String pendingJson;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
  }

  @Entity(name = "MessageEntity")
  @Table(name = "hd_messages")
  public static class MessageEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 36)
    public String conversationId;

    @Column(nullable = false, length = 16)
    public String role;

    @Column(nullable = false, columnDefinition = "text")
    public String content;

    @Column(nullable = false, columnDefinition = "text")
    public String sourcesJson = "[]";

    @Column(columnDefinition = "text")
    public String proposalJson;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
  }

  @Entity(name = "TicketEntity")
  @Table(name = "hd_tickets")
  public static class TicketEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 40, unique = true)
    public String number = "HD-" + UUID.randomUUID().toString();

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, columnDefinition = "text")
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Status status = Status.OPEN;

    @Column(nullable = false, length = 8)
    public String departmentId;

    @Column(nullable = false, length = 36)
    public String creatorId;

    @Column(length = 36)
    public String assigneeId;

    @Column(columnDefinition = "text")
    public String resolution;

    public Integer rating;

    @Column(nullable = false, length = 160)
    public String idempotencyKey;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
  }

  @Entity(name = "CommentEntity")
  @Table(name = "hd_comments")
  public static class CommentEntity {
    @Id
    @Column(length = 36)
    public String id = id();

    @Column(nullable = false, length = 36)
    public String ticketId;

    @Column(nullable = false, length = 36)
    public String authorId;

    @Column(nullable = false, columnDefinition = "text")
    public String content;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
  }

  @Entity(name = "DecisionEntity")
  @Table(name = "hd_decisions")
  public static class DecisionEntity {
    @Id
    @Column(length = 36)
    public String id;

    @Column(nullable = false, length = 36)
    public String conversationId;

    @Column(nullable = false)
    public boolean approved;

    @Column(length = 36)
    public String ticketId;

    @Column(nullable = false)
    public boolean resumed;

    @Column(columnDefinition = "text")
    public String answer;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
  }
}

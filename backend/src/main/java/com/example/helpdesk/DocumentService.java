package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
// Java 保管文档权限与版本元数据；原件存储、文本解析和检索索引交给 Python 服务。
public class DocumentService {
  final BusinessRepository repo;
  final AiClient ai;
  final Locks locks;

  /**
   * 用途：注入文档元数据、索引调用及并发控制依赖。
   * 输入：repo：仓储；ai：AI 客户端；locks：进程锁。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  DocumentService(BusinessRepository repo, AiClient ai, Locks locks) {
    this.repo = repo;
    this.ai = ai;
    this.locks = locks;
  }

  /**
   * 用途：判断文档是否有效且对用户可见。
   * 输入：actor：用户；d：文档实体。
   * 输出：布尔值。
   */
  boolean visible(UserEntity actor, DocumentEntity d) {
    // PUBLIC 表示登录用户可见；部门文档由所属部门和管理员访问，删除后立即不可见。
    return d.active
        && (actor.role == Role.ADMIN
            || d.visibility == Visibility.PUBLIC
            || actor.departmentId.equals(d.departmentId));
  }

  /**
   * 用途：校验用户能否管理目标部门文档。
   * 输入：actor：用户；department：目标部门。
   * 输出：void；无权限时抛业务异常。
   */
  void manage(UserEntity actor, String department) {
    if (actor.role == Role.EMPLOYEE
        || (actor.role != Role.ADMIN && !actor.departmentId.equals(department))) throw forbidden();
  }

  /**
   * 用途：加载文档并检查存在性与可见性。
   * 输入：actor：用户；id：文档 ID。
   * 输出：有效文档实体；非法访问抛异常。
   */
  DocumentEntity require(UserEntity actor, String id) {
    DocumentEntity d = repo.find(DocumentEntity.class, id);
    if (d == null || !d.active) throw missing();
    if (!visible(actor, d)) throw forbidden();
    return d;
  }

  /**
   * 用途：将文档实体转换为前端 DTO。
   * 输入：d：文档实体。
   * 输出：文档元数据、索引状态及模式。
   */
  Document view(DocumentEntity d) {
    return new Document(
        d.id,
        d.title,
        d.filename,
        d.departmentId,
        d.visibility,
        d.version,
        d.status,
        d.chunkCount,
        d.error,
        d.createdAt,
        d.indexMode);
  }

  /**
   * 用途：筛选当前用户可见的有效文档。
   * 输入：actor：用户。
   * 输出：文档实体列表。
   */
  List<DocumentEntity> accessible(UserEntity actor) {
    return repo
        .query(DocumentEntity.class, "where e.active=true order by e.createdAt desc")
        .stream()
        .filter(d -> visible(actor, d))
        .toList();
  }

  /**
   * 用途：查询可见文档并同步 Python 索引状态。
   * 输入：actor：用户。
   * 输出：文档 DTO 列表；可能更新缓存状态。
   */
  public List<Document> list(UserEntity actor) {
    List<DocumentEntity> docs = accessible(actor);
    refresh(docs);
    return docs.stream().map(this::view).toList();
  }

  /**
   * 用途：批量查询索引进度并更新本地元数据。
   * 输入：docs：可见文档实体列表。
   * 输出：void；仅更新仍匹配的版本，依赖失败时保留缓存。
   */
  private void refresh(List<DocumentEntity> docs) {
    List<String> ids = docs.stream().filter(d -> d.indexAccepted).map(d -> d.id).toList();
    if (ids.isEmpty()) return;
    try {
      IndexStates result =
          ai.post("/internal/documents/status", Map.of("ids", ids), IndexStates.class);
      if (result.documents() == null) return;
      Map<String, IndexState> states = new HashMap<>();
      result.documents().forEach(s -> states.put(s.id(), s));
      for (DocumentEntity d : docs) {
        IndexState state = states.get(d.id);
        if (state == null || !d.indexAccepted) continue;
        if (!Set.of("QUEUED", "PROCESSING", "READY", "FAILED").contains(state.status())) continue;
        synchronized (locks.forKey("doc:" + d.id)) {
          DocumentEntity current = repo.find(DocumentEntity.class, d.id);
          if (current == null
              || !current.active
              || !current.indexAccepted
              || current.version != d.version) continue;
          d.status = state.status();
          d.chunkCount = Math.max(0, state.chunkCount());
          d.error = state.error() == null ? null : "文档解析或索引失败，请检查文件后重新上传";
          d.indexMode =
              Set.of("hybrid", "keyword", "vector", "keyword-only")
                      .contains(Objects.toString(state.indexMode(), ""))
                  ? state.indexMode()
                  : null;
          current.status = d.status;
          current.chunkCount = d.chunkCount;
          current.error = d.error;
          current.indexMode = d.indexMode;
          repo.save(current);
        }
      }
    } catch (Failure ignored) {
      /* Cached state remains honest; status page reports dependency readiness. */
    }
  }

  /**
   * 用途：建立问答可用的授权文档集合。
   * 输入：actor：当前用户。
   * 输出：已接收且 READY 的可见文档 ID 列表。
   */
  public List<String> allowedIds(UserEntity actor) {
    // RAG 的授权入口：只能把用户有权访问且当前索引已就绪的文档 ID 交给 Python。
    List<DocumentEntity> docs = accessible(actor);
    refresh(docs);
    return docs.stream()
        .filter(d -> d.indexAccepted && d.status.equals("READY"))
        .map(d -> d.id)
        .toList();
  }

  /**
   * 用途：复核每个引用的权限及版本。
   * 输入：actor：用户；sources：可空来源列表。
   * 输出：仍可访问且版本一致的来源列表。
   */
  public List<Source> filterSources(UserEntity actor, List<Source> sources) {
    // 生成耗时期间文档可能更新、删除或改变权限，返回答案和读取历史时都要重新校验。
    if (sources == null) return List.of();
    return sources.stream()
        .filter(
            s -> {
              DocumentEntity d = repo.find(DocumentEntity.class, s.documentId());
              return d != null && visible(actor, d) && d.indexAccepted && d.version == s.version();
            })
        .toList();
  }

  /**
   * 用途：校验文件并创建或递增版本，提交 Python 索引。
   * 输入：actor：用户；id：更新时的文档 ID；file、title、department、visibility：文件及元数据。
   * 输出：文档 DTO；更新数据库并调用内部上传接口。
   */
  public Document upload(
      UserEntity actor,
      String id,
      MultipartFile file,
      String title,
      String department,
      Visibility visibility) {
    if (!Set.of("IT", "HR").contains(department) || visibility == null) throw bad("部门或可见范围无效");
    manage(actor, department);
    if (title == null || title.isBlank() || title.trim().length() > 200)
      throw bad("标题不能为空且最多 200 个字符");
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw bad("文件不能为空且不能超过 10 MB");
    String original =
        Objects.toString(file.getOriginalFilename(), "document.txt").replace('\\', '/');
    String filename =
        original.substring(original.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
    if (filename.isBlank()
        || filename.length() > 255
        || !filename.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|pdf|docx)$"))
      throw bad("仅支持 TXT、MD、PDF、DOCX 文件");
    String documentId = id == null ? Entities.id() : id;
    synchronized (locks.forKey("doc:" + documentId)) {
      DocumentEntity d =
          repo.tx(
              () -> {
                DocumentEntity next;
                if (id == null) {
                  next = new DocumentEntity();
                  next.id = documentId;
                } else {
                  next = require(actor, id);
                  manage(actor, next.departmentId);
                  next.version++;
                }
                next.title = title.trim();
                next.filename = filename;
                next.departmentId = department;
                next.visibility = visibility;
                next.status = "QUEUED";
                next.chunkCount = 0;
                next.error = null;
                next.indexAccepted = false;
                // 先让新版本退出可检索集合，Python 确认接收且索引 READY 后才能参与问答。
                return repo.save(next);
              });
      try {
        byte[] content = file.getBytes();
        ai.post(
            "/internal/documents",
            Map.of(
                "id",
                d.id,
                "title",
                d.title,
                "filename",
                d.filename,
                "departmentId",
                d.departmentId,
                "visibility",
                d.visibility,
                "version",
                d.version,
                "contentBase64",
                Base64.getEncoder().encodeToString(content)),
            Object.class);
        d.indexAccepted = true;
        repo.save(d);
        return view(d);
      } catch (Exception ex) {
        d.status = "FAILED";
        d.error = "文档提交索引失败，请重新上传";
        repo.save(d);
        throw unavailable();
      }
    }
  }

  /**
   * 用途：先撤销可见性，再尝试清理外部索引。
   * 输入：actor：用户；id：文档 ID。
   * 输出：void；持久化删除标记并触发清理。
   */
  public void delete(UserEntity actor, String id) {
    synchronized (locks.forKey("doc:" + id)) {
      DocumentEntity d = require(actor, id);
      manage(actor, d.departmentId);
      d.active = false;
      // 先持久化逻辑删除，立即阻止访问；外部索引清理失败由定时任务重试，避免重新暴露内容。
      d.indexAccepted = false;
      d.cleanupDone = false;
      repo.save(d);
      cleanup(d);
    }
  }

  /**
   * 用途：校验访问权限后读取原件。
   * 输入：actor：用户；id：文档 ID。
   * 输出：文件名与字节组成的 Download。
   */
  public Download download(UserEntity actor, String id) {
    synchronized (locks.forKey("doc:" + id)) {
      DocumentEntity d = require(actor, id);
      if (!d.indexAccepted) throw conflict("文档原件尚未提交成功");
      return new Download(d.filename, ai.download(id));
    }
  }

  public record Download(String filename, byte[] content) {}

  /**
   * 用途：定时重试未完成的索引删除。
   * 输入：无参数，从数据库读取待清理项。
   * 输出：void；依次调用清理并更新完成状态。
   */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${app.cleanup.interval-ms:60000}",
      initialDelayString = "${app.cleanup.initial-delay-ms:60000}")
  public void retryDeletions() {
    for (DocumentEntity candidate :
        repo.query(DocumentEntity.class, "where e.active=false and e.cleanupDone=false")) {
      synchronized (locks.forKey("doc:" + candidate.id)) {
        DocumentEntity current = repo.find(DocumentEntity.class, candidate.id);
        if (current != null && !current.active && !current.cleanupDone) cleanup(current);
      }
    }
  }

  /**
   * 用途：删除 Python 索引，保留失败项供后续重试。
   * 输入：document：已逻辑删除的文档。
   * 输出：void；成功标记 cleanupDone，失败保留待清理状态。
   */
  private void cleanup(DocumentEntity document) {
    try {
      // Durable tombstone precedes this external call. No database transaction spans the request.
      ai.delete("/internal/documents/" + document.id);
      document.cleanupDone = true;
      repo.save(document);
    } catch (Failure ignored) {
      // The next scheduler pass (including after restart) retries until Python acknowledges.
    }
  }
}

package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  final BusinessRepository repo;
  final AiClient ai;
  final Locks locks;

  DocumentService(BusinessRepository repo, AiClient ai, Locks locks) {
    this.repo = repo;
    this.ai = ai;
    this.locks = locks;
  }

  boolean visible(UserEntity actor, DocumentEntity d) {
    return d.active
        && (actor.role == Role.ADMIN
            || d.visibility == Visibility.PUBLIC
            || actor.departmentId.equals(d.departmentId));
  }

  void manage(UserEntity actor, String department) {
    if (actor.role == Role.EMPLOYEE
        || (actor.role != Role.ADMIN && !actor.departmentId.equals(department))) throw forbidden();
  }

  DocumentEntity require(UserEntity actor, String id) {
    DocumentEntity d = repo.find(DocumentEntity.class, id);
    if (d == null || !d.active) throw missing();
    if (!visible(actor, d)) throw forbidden();
    return d;
  }

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

  List<DocumentEntity> accessible(UserEntity actor) {
    return repo
        .query(DocumentEntity.class, "where e.active=true order by e.createdAt desc")
        .stream()
        .filter(d -> visible(actor, d))
        .toList();
  }

  public List<Document> list(UserEntity actor) {
    List<DocumentEntity> docs = accessible(actor);
    refresh(docs);
    return docs.stream().map(this::view).toList();
  }

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

  public List<String> allowedIds(UserEntity actor) {
    List<DocumentEntity> docs = accessible(actor);
    refresh(docs);
    return docs.stream()
        .filter(d -> d.indexAccepted && d.status.equals("READY"))
        .map(d -> d.id)
        .toList();
  }

  public List<Source> filterSources(UserEntity actor, List<Source> sources) {
    if (sources == null) return List.of();
    return sources.stream()
        .filter(
            s -> {
              DocumentEntity d = repo.find(DocumentEntity.class, s.documentId());
              return d != null && visible(actor, d) && d.indexAccepted && d.version == s.version();
            })
        .toList();
  }

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

  public void delete(UserEntity actor, String id) {
    synchronized (locks.forKey("doc:" + id)) {
      DocumentEntity d = require(actor, id);
      manage(actor, d.departmentId);
      d.active = false;
      d.indexAccepted = false;
      d.cleanupDone = false;
      repo.save(d);
      cleanup(d);
    }
  }

  public Download download(UserEntity actor, String id) {
    synchronized (locks.forKey("doc:" + id)) {
      DocumentEntity d = require(actor, id);
      if (!d.indexAccepted) throw conflict("文档原件尚未提交成功");
      return new Download(d.filename, ai.download(id));
    }
  }

  public record Download(String filename, byte[] content) {}

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

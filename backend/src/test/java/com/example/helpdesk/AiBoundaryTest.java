package com.example.helpdesk;

import static com.example.helpdesk.Entities.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:boundary;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@AutoConfigureMockMvc(
    printOnlyOnFailure = false,
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AiBoundaryTest {
  static final String PASSWORD = UUID.randomUUID().toString(),
      SERVICE_TOKEN = UUID.randomUUID().toString();
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final Map<String, JsonNode> documents = new ConcurrentHashMap<>();
  static final AtomicBoolean failResume = new AtomicBoolean(false);
  static final AtomicBoolean failIngest = new AtomicBoolean(false);
  static final AtomicBoolean failDelete = new AtomicBoolean(false);
  static final AtomicBoolean loseChatResponse = new AtomicBoolean(false);
  static final Map<String, Map<String, Object>> pendingStates = new ConcurrentHashMap<>();
  static volatile JsonNode lastChat, lastResume;
  static final HttpServer ai = start();

  static HttpServer start() {
    try {
      var s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(
          "/",
          ex -> {
            String path = ex.getRequestURI().getPath();
            int status = 200;
            Object response = Map.of("status", "ok");
            if (!SERVICE_TOKEN.equals(ex.getRequestHeaders().getFirst("X-Service-Token"))) {
              status = 403;
              response = Map.of("detail", "forbidden");
            } else if (path.equals("/internal/documents") && ex.getRequestMethod().equals("POST")) {
              JsonNode doc = MAPPER.readTree(ex.getRequestBody());
              if (failIngest.get()) {
                status = 503;
              } else documents.put(doc.path("id").asText(), doc);
              response = Map.of("status", "QUEUED");
            } else if (path.equals("/internal/documents/status")) {
              var ids = MAPPER.readTree(ex.getRequestBody()).path("ids");
              List<Object> result = new ArrayList<>();
              for (JsonNode id : ids)
                result.add(
                    Map.of(
                        "id",
                        id.asText(),
                        "status",
                        "READY",
                        "chunkCount",
                        1,
                        "indexMode",
                        "keyword"));
              response = Map.of("documents", result);
            } else if (path.endsWith("/download")) {
              byte[] out = "authorized original".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(200, out.length);
              ex.getResponseBody().write(out);
              ex.close();
              return;
            } else if (path.startsWith("/internal/documents/")
                && ex.getRequestMethod().equals("DELETE")) {
              if (failDelete.get()) status = 503;
              else documents.remove(path.substring(path.lastIndexOf('/') + 1));
              response = Map.of("deleted", true);
            } else if (path.equals("/internal/chat/state")) {
              String conversation =
                  MAPPER.readTree(ex.getRequestBody()).path("conversationId").asText();
              Map<String, Object> emptyState = new LinkedHashMap<>();
              emptyState.put("question", null);
              emptyState.put("pending", null);
              response = pendingStates.getOrDefault(conversation, emptyState);
            } else if (path.startsWith("/internal/conversations/")
                && ex.getRequestMethod().equals("DELETE")) {
              pendingStates.remove(path.substring(path.lastIndexOf('/') + 1));
              response = Map.of("deleted", true);
            } else if (path.equals("/internal/chat")) {
              lastChat = MAPPER.readTree(ex.getRequestBody());
              List<Object> sources = new ArrayList<>();
              for (JsonNode d : documents.values())
                sources.add(
                    Map.of(
                        "documentId",
                        d.path("id").asText(),
                        "title",
                        d.path("title").asText(),
                        "filename",
                        d.path("filename").asText(),
                        "chunkIndex",
                        0,
                        "content",
                        "document evidence",
                        "score",
                        1.0,
                        "version",
                        d.path("version").asInt()));
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("answer", sources.isEmpty() ? "仅检索，未调用模型" : "仅检索，未调用模型：document evidence");
              result.put("sources", sources);
              result.put("mode", "retrieval-only");
              result.put("trace", List.of("retrieve", "interrupt"));
              result.put(
                  "proposal",
                  lastChat.path("requestTicket").asBoolean()
                      ? Map.of(
                          "id",
                          UUID.randomUUID().toString(),
                          "title",
                          "Repair VPN",
                          "description",
                          "VPN inaccessible",
                          "priority",
                          "HIGH")
                      : null);
              response = result;
              if (result.get("proposal") != null)
                pendingStates.put(
                    lastChat.path("conversationId").asText(),
                    Map.of("question", lastChat.path("question").asText(), "pending", result));
              if (loseChatResponse.getAndSet(false)) {
                status = 503;
                response = Map.of("detail", "response lost after graph commit");
              }
            } else if (path.equals("/internal/chat/confirm")) {
              lastResume = MAPPER.readTree(ex.getRequestBody());
              if (failResume.getAndSet(false)) status = 503;
              else pendingStates.remove(lastResume.path("conversationId").asText());
              response = Map.of("answer", "决定已记录");
            }
            byte[] bytes = MAPPER.writeValueAsBytes(response);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
          });
      s.start();
      return s;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    for (String name : List.of("admin", "agent", "employee", "hr"))
      r.add("app.seed." + name, () -> PASSWORD);
    r.add("app.ai.base-url", () -> "http://127.0.0.1:" + ai.getAddress().getPort());
    r.add("app.ai.token", () -> SERVICE_TOKEN);
  }

  @AfterAll
  static void stop() {
    ai.stop(0);
  }

  @BeforeEach
  void isolateDependencyState() {
    documents.clear();
    failIngest.set(false);
    failResume.set(false);
    failDelete.set(false);
    loseChatResponse.set(false);
    pendingStates.clear();
    lastChat = null;
    lastResume = null;
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired BusinessRepository repo;
  @Autowired DocumentService documentService;

  JsonNode data(String text) {
    return json.readTree(text).path("data");
  }

  String login(String user) throws Exception {
    return data(mvc.perform(
                post("/api/auth/login")
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(Map.of("username", user, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString())
        .path("token")
        .asText();
  }

  String conversation(String token) throws Exception {
    return data(mvc.perform(
                post("/api/conversations")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json")
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString())
        .path("id")
        .asText();
  }

  @Test
  void failedResumeKeepsOneDurableTicketAndPendingProposalUntilRetry() throws Exception {
    String token = login("employee"), id = conversation(token);
    JsonNode answer =
        data(
            mvc.perform(
                    post("/api/conversations/" + id + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"question\":\"Please help with VPN\",\"requestTicket\":true}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String proposal = answer.path("proposal").path("id").asText();
    String payload = json.writeValueAsString(Map.of("proposalId", proposal, "approved", true));
    failResume.set(true);
    mvc.perform(
            post("/api/conversations/" + id + "/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isServiceUnavailable());
    assertThat(repo.query(TicketEntity.class, "where e.idempotencyKey=?1", "proposal:" + proposal))
        .hasSize(1);
    String committedTicket =
        repo.query(TicketEntity.class, "where e.idempotencyKey=?1", "proposal:" + proposal)
            .getFirst()
            .id;
    mvc.perform(get("/api/conversations/" + id).header("Authorization", "Bearer " + token))
        .andExpect(jsonPath("$.data.pendingProposal.id").value(proposal));
    mvc.perform(
            post("/api/conversations/" + id + "/messages")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"question\":\"new question\"}"))
        .andExpect(status().isConflict());
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/conversations/" + id + "/confirm")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.ticket.id").value(committedTicket));
    assertThat(repo.query(TicketEntity.class, "where e.idempotencyKey=?1", "proposal:" + proposal))
        .hasSize(1);
    assertThat(lastResume.path("ticketId").asText()).isEqualTo(committedTicket);
    mvc.perform(
            post("/api/conversations/" + id + "/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(Map.of("proposalId", proposal, "approved", false))))
        .andExpect(status().isConflict());
  }

  @Test
  void documentScopeAndTombstonesPreventUnauthorizedRetrievalAndDownload() throws Exception {
    String admin = login("admin"),
        agent = login("agent"),
        employee = login("employee"),
        hr = login("hr");
    var file =
        new MockMultipartFile(
            "file", "policy.txt", "text/plain", "Internal policy".getBytes(StandardCharsets.UTF_8));
    mvc.perform(
            multipart("/api/documents")
                .file(file)
                .param("title", "Denied")
                .param("departmentId", "HR")
                .param("visibility", "DEPARTMENT")
                .header("Authorization", "Bearer " + agent))
        .andExpect(status().isForbidden());
    String id =
        data(mvc.perform(
                    multipart("/api/documents")
                        .file(file)
                        .param("title", "IT policy")
                        .param("departmentId", "IT")
                        .param("visibility", "DEPARTMENT")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    mvc.perform(get("/api/documents/" + id + "/download").header("Authorization", "Bearer " + hr))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/documents/" + id + "/download").header("Authorization", "Bearer " + employee))
        .andExpect(status().isOk())
        .andExpect(content().bytes("authorized original".getBytes(StandardCharsets.UTF_8)));
    String cid = conversation(hr);
    mvc.perform(
            post("/api/conversations/" + cid + "/messages")
                .header("Authorization", "Bearer " + hr)
                .contentType("application/json")
                .content("{\"question\":\"What is IT policy?\"}"))
        .andExpect(status().isConflict());
    assertThat(lastChat.path("allowedDocumentIds").toString()).doesNotContain(id);
    mvc.perform(delete("/api/documents/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/documents/" + id + "/download").header("Authorization", "Bearer " + employee))
        .andExpect(status().isNotFound());
  }

  @Test
  void failedReplacementNeverGrantsOldIndexAccess() throws Exception {
    String admin = login("admin"), emp = login("employee");
    var file =
        new MockMultipartFile(
            "file", "version.txt", "text/plain", "version1".getBytes(StandardCharsets.UTF_8));
    String id =
        data(mvc.perform(
                    multipart("/api/documents")
                        .file(file)
                        .param("title", "Versioned")
                        .param("departmentId", "IT")
                        .param("visibility", "PUBLIC")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    failIngest.set(true);
    try {
      mvc.perform(
              multipart("/api/documents/" + id)
                  .file(file)
                  .with(
                      r -> {
                        r.setMethod("PUT");
                        return r;
                      })
                  .param("title", "Versioned")
                  .param("departmentId", "IT")
                  .param("visibility", "PUBLIC")
                  .header("Authorization", "Bearer " + admin))
          .andExpect(status().isServiceUnavailable());
    } finally {
      failIngest.set(false);
    }
    String cid = conversation(emp);
    mvc.perform(
            post("/api/conversations/" + cid + "/messages")
                .header("Authorization", "Bearer " + emp)
                .contentType("application/json")
                .content("{\"question\":\"Find version\"}"))
        .andExpect(status().isConflict());
    assertThat(lastChat.path("allowedDocumentIds").toString()).doesNotContain(id);
  }

  @Test
  void historyRedactsEntireAssistantAnswerAfterVisibilityChangeAndDeletion() throws Exception {
    String admin = login("admin"), employee = login("employee");
    var file =
        new MockMultipartFile(
            "file", "private.txt", "text/plain", "private text".getBytes(StandardCharsets.UTF_8));
    String id =
        data(mvc.perform(
                    multipart("/api/documents")
                        .file(file)
                        .param("title", "Guidance")
                        .param("departmentId", "IT")
                        .param("visibility", "PUBLIC")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    String cid = conversation(employee);
    mvc.perform(
            post("/api/conversations/" + cid + "/messages")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content("{\"question\":\"Read guidance\"}"))
        .andExpect(status().isOk());
    String before =
        mvc.perform(get("/api/conversations/" + cid).header("Authorization", "Bearer " + employee))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(before).contains("document evidence");
    mvc.perform(
            multipart("/api/documents/" + id)
                .file(file)
                .with(
                    r -> {
                      r.setMethod("PUT");
                      return r;
                    })
                .param("title", "Guidance")
                .param("departmentId", "HR")
                .param("visibility", "DEPARTMENT")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    String after =
        mvc.perform(get("/api/conversations/" + cid).header("Authorization", "Bearer " + employee))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(after).doesNotContain("document evidence").contains("Read guidance");
    mvc.perform(
            multipart("/api/documents/" + id)
                .file(file)
                .with(
                    r -> {
                      r.setMethod("PUT");
                      return r;
                    })
                .param("title", "Guidance")
                .param("departmentId", "IT")
                .param("visibility", "PUBLIC")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    String second = conversation(employee);
    mvc.perform(
            post("/api/conversations/" + second + "/messages")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content("{\"question\":\"Read guidance again\"}"))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/documents/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    String deleted =
        mvc.perform(
                get("/api/conversations/" + second).header("Authorization", "Bearer " + employee))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(deleted).doesNotContain("document evidence").contains("Read guidance again");
  }

  @Test
  void unavailablePythonDeleteIsRetriedFromDurableTombstone() throws Exception {
    String admin = login("admin");
    var file =
        new MockMultipartFile(
            "file", "delete.txt", "text/plain", "delete me".getBytes(StandardCharsets.UTF_8));
    String id =
        data(mvc.perform(
                    multipart("/api/documents")
                        .file(file)
                        .param("title", "Cleanup")
                        .param("departmentId", "IT")
                        .param("visibility", "DEPARTMENT")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    failDelete.set(true);
    mvc.perform(delete("/api/documents/" + id).header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    assertThat(documents).containsKey(id);
    assertThat(repo.find(DocumentEntity.class, id).active).isFalse();
    mvc.perform(
            get("/api/documents/" + id + "/download").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
    failDelete.set(false);
    documentService.retryDeletions();
    assertThat(documents).doesNotContainKey(id);
  }

  @Test
  void lostDraftResponseIsRecoveredExactlyOnceAndStaleEvidenceIsMasked() throws Exception {
    String admin = login("admin"), employee = login("employee");
    var file =
        new MockMultipartFile(
            "file", "recover.txt", "text/plain", "old text".getBytes(StandardCharsets.UTF_8));
    String documentId =
        data(mvc.perform(
                    multipart("/api/documents")
                        .file(file)
                        .param("title", "Recovery")
                        .param("departmentId", "IT")
                        .param("visibility", "PUBLIC")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .path("id")
            .asText();
    String cid = conversation(employee);
    loseChatResponse.set(true);
    mvc.perform(
            post("/api/conversations/" + cid + "/messages")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content("{\"question\":\"Repair my VPN\",\"requestTicket\":true}"))
        .andExpect(status().isServiceUnavailable());
    assertThat(repo.find(ConversationEntity.class, cid).pendingJson).isNull();
    mvc.perform(
            multipart("/api/documents/" + documentId)
                .file(file)
                .with(
                    r -> {
                      r.setMethod("PUT");
                      return r;
                    })
                .param("title", "Recovery")
                .param("departmentId", "HR")
                .param("visibility", "DEPARTMENT")
                .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    JsonNode recovered =
        data(
            mvc.perform(
                    get("/api/conversations/" + cid).header("Authorization", "Bearer " + employee))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(recovered.path("pendingProposal").path("id").asText()).isNotBlank();
    assertThat(recovered.path("messages").size()).isEqualTo(2);
    assertThat(recovered.toString()).doesNotContain("document evidence");
    mvc.perform(get("/api/conversations/" + cid).header("Authorization", "Bearer " + employee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages.length()").value(2));
    mvc.perform(
            post("/api/conversations/" + cid + "/messages")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content("{\"question\":\"Another question\"}"))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/conversations/" + cid + "/confirm")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "proposalId",
                            recovered.path("pendingProposal").path("id").asText(),
                            "approved",
                            true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.ticket.title").value("Repair VPN"));
  }
}

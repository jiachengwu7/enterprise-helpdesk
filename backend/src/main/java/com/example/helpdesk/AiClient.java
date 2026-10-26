package com.example.helpdesk;

import static com.example.helpdesk.Failures.*;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** All dependency I/O happens outside business database transactions. */
@Component
public class AiClient {
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String base, token;
  private final ObjectMapper json;

  AiClient(
      @Value("${app.ai.base-url}") String base,
      @Value("${app.ai.token}") String token,
      ObjectMapper json) {
    this.base = base.replaceAll("/$", "");
    this.token = token;
    this.json = json;
  }

  public <T> T post(String path, Object body, Class<T> type) {
    try {
      return json.readValue(call("POST", path, json.writeValueAsBytes(body)), type);
    } catch (Failure ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  public void delete(String path) {
    call("DELETE", path, null);
  }

  public byte[] download(String id) {
    return call("GET", "/internal/documents/" + id + "/download", null);
  }

  public Map<String, Object> health() {
    try {
      JsonNode node = json.readTree(call("GET", "/health", null));
      Map<String, Object> result = new LinkedHashMap<>();
      for (String key :
          List.of(
              "status",
              "modelConfigured",
              "embeddingConfigured",
              "indexMode",
              "database",
              "redis",
              "storage",
              "version"))
        if (node.has(key)) result.put(key, json.convertValue(node.get(key), Object.class));
      return result;
    } catch (Exception e) {
      return Map.of("status", "unavailable");
    }
  }

  private byte[] call(String method, String path, byte[] body) {
    try {
      if (path.startsWith("/internal/") && token.isBlank()) throw unavailable();
      var builder =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(path.equals("/health") ? 3 : 120))
              .header("Accept", "application/json")
              .header("X-Service-Token", token);
      if (body != null) builder.header("Content-Type", "application/json");
      var response =
          client.send(
              builder
                  .method(
                      method,
                      body == null
                          ? HttpRequest.BodyPublishers.noBody()
                          : HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        if (response.statusCode() == 409) throw conflict("AI 会话状态冲突，请刷新后重试");
        if (response.statusCode() == 422 || response.statusCode() == 400)
          throw bad("AI 服务无法处理该请求，请检查文件或问题内容");
        throw unavailable();
      }
      return response.body();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Failure ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    }
  }
}

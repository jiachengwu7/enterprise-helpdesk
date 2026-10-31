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

  /**
   * 用途：初始化内部 HTTP 客户端的目标地址与认证配置。
   * 输入：base：Python 服务地址；token：内部服务令牌；json：JSON 编解码器。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  AiClient(
      @Value("${app.ai.base-url}") String base,
      @Value("${app.ai.token}") String token,
      ObjectMapper json) {
    this.base = base.replaceAll("/$", "");
    this.token = token;
    this.json = json;
  }

  /**
   * 用途：向 Python 发送 JSON 请求并解码业务响应。
   * 输入：path：内部路径；body：请求对象；type：响应类型。
   * 输出：类型化响应；失败转换为安全业务异常。
   */
  public <T> T post(String path, Object body, Class<T> type) {
    try {
      return json.readValue(call("POST", path, json.writeValueAsBytes(body)), type);
    } catch (Failure ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  /**
   * 用途：调用 Python 删除接口。
   * 输入：path：内部资源路径。
   * 输出：void；发生远程删除副作用。
   */
  public void delete(String path) {
    call("DELETE", path, null);
  }

  /**
   * 用途：下载 Python 存储的文档原件。
   * 输入：id：文档 ID。
   * 输出：文件字节数组。
   */
  public byte[] download(String id) {
    return call("GET", "/internal/documents/" + id + "/download", null);
  }

  /**
   * 用途：探测 Python 健康状态并隐藏连接异常细节。
   * 输入：无参数，使用配置的服务地址。
   * 输出：状态映射；失败时返回不可用状态。
   */
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

  /**
   * 用途：统一发送带服务令牌和超时的内部 HTTP 请求。
   * 输入：method、path：方法及路径；body：可空请求字节。
   * 输出：响应字节；非成功状态转为业务异常。
   */
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

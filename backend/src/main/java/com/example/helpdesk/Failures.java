package com.example.helpdesk;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

public final class Failures {
  /**
   * 用途：禁止外部实例化静态定义容器。
   * 输入：无参数。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  private Failures() {}

  /**
   * 用途：构造参数错误异常。
   * 输入：message：安全提示。
   * 输出：状态为 400 的 Failure。
   */
  static Failure bad(String message) {
    return new Failure(400, message);
  }

  /**
   * 用途：构造无权限异常。
   * 输入：无参数。
   * 输出：状态为 403 的 Failure。
   */
  static Failure forbidden() {
    return new Failure(403, "没有权限执行此操作");
  }

  /**
   * 用途：构造资源不存在异常。
   * 输入：无参数。
   * 输出：状态为 404 的 Failure。
   */
  static Failure missing() {
    return new Failure(404, "资源不存在或无权访问");
  }

  /**
   * 用途：构造业务状态冲突异常。
   * 输入：message：冲突原因。
   * 输出：状态为 409 的 Failure。
   */
  static Failure conflict(String message) {
    return new Failure(409, message);
  }

  /**
   * 用途：构造依赖不可用异常。
   * 输入：无参数。
   * 输出：状态为 503 的 Failure。
   */
  static Failure unavailable() {
    return new Failure(503, "AI 服务暂时不可用，请稍后重试");
  }

  static final class Failure extends RuntimeException {
    final int status;

    /**
     * 用途：初始化携带 HTTP 状态的业务异常。
     * 输入：status：状态码；message：安全提示。
     * 输出：构造函数，无返回值；保存异常状态与消息。
     */
    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  @RestControllerAdvice
  public static class Handler {
    /**
     * 用途：将业务异常转换为统一响应。
     * 输入：ex：Failure。
     * 输出：包含原状态码与安全消息的 ResponseEntity。
     */
    @ExceptionHandler(Failure.class)
    ResponseEntity<Api.Envelope<Void>> business(Failure ex) {
      return error(ex.status, ex.getMessage());
    }

    /**
     * 用途：处理请求字段或类型校验错误。
     * 输入：ex：校验异常。
     * 输出：HTTP 400 的统一响应。
     */
    @ExceptionHandler({
      MethodArgumentNotValidException.class,
      HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class,
      MissingServletRequestPartException.class,
      IllegalArgumentException.class
    })
    ResponseEntity<Api.Envelope<Void>> invalid(Exception ex) {
      return error(400, "请求参数无效，请检查内容和必填项");
    }

    /**
     * 用途：处理上传体积超限。
     * 输入：ex：上传大小异常。
     * 输出：HTTP 413 的统一响应。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Api.Envelope<Void>> size(Exception ex) {
      return error(413, "文件不能超过 10 MB");
    }

    /**
     * 用途：处理数据库唯一约束冲突。
     * 输入：ex：数据完整性异常。
     * 输出：HTTP 409 的统一响应。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Api.Envelope<Void>> duplicate(Exception ex) {
      return error(409, "记录已存在或并发更新冲突，请刷新重试");
    }

    /**
     * 用途：隐藏未知异常的内部细节。
     * 输入：ex：未识别异常。
     * 输出：HTTP 500 的统一响应。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Api.Envelope<Void>> other(Exception ex) {
      org.slf4j.LoggerFactory.getLogger(Handler.class)
          .error("Request failed: {}", ex.getClass().getSimpleName());
      return error(500, "服务暂时无法完成请求");
    }

    /**
     * 用途：构造错误 HTTP 响应。
     * 输入：status：状态码；message：安全提示。
     * 输出：Envelope 包装的 ResponseEntity。
     */
    private ResponseEntity<Api.Envelope<Void>> error(int status, String message) {
      return ResponseEntity.status(status).body(new Api.Envelope<>(status, message, null));
    }
  }
}

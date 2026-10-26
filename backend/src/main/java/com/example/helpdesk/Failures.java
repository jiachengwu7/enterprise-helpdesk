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
  private Failures() {}

  static Failure bad(String message) {
    return new Failure(400, message);
  }

  static Failure forbidden() {
    return new Failure(403, "没有权限执行此操作");
  }

  static Failure missing() {
    return new Failure(404, "资源不存在或无权访问");
  }

  static Failure conflict(String message) {
    return new Failure(409, message);
  }

  static Failure unavailable() {
    return new Failure(503, "AI 服务暂时不可用，请稍后重试");
  }

  static final class Failure extends RuntimeException {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  @RestControllerAdvice
  public static class Handler {
    @ExceptionHandler(Failure.class)
    ResponseEntity<Api.Envelope<Void>> business(Failure ex) {
      return error(ex.status, ex.getMessage());
    }

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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Api.Envelope<Void>> size(Exception ex) {
      return error(413, "文件不能超过 10 MB");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Api.Envelope<Void>> duplicate(Exception ex) {
      return error(409, "记录已存在或并发更新冲突，请刷新重试");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Api.Envelope<Void>> other(Exception ex) {
      org.slf4j.LoggerFactory.getLogger(Handler.class)
          .error("Request failed: {}", ex.getClass().getSimpleName());
      return error(500, "服务暂时无法完成请求");
    }

    private ResponseEntity<Api.Envelope<Void>> error(int status, String message) {
      return ResponseEntity.status(status).body(new Api.Envelope<>(status, message, null));
    }
  }
}

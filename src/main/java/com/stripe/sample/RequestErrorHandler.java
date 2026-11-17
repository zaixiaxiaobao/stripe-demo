package com.stripe.sample;

import java.util.Map;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class RequestErrorHandler extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(RequestErrorHandler.class);

  @ExceptionHandler(SQLException.class)
  public ResponseEntity<?> databaseUnavailable(SQLException error) {
    LOG.error("Database request failed: {}, SQLState={}, code={}",
        error.getClass().getSimpleName(), error.getSQLState(), error.getErrorCode());
    return ResponseEntity.status(503)
        .body(Map.of("error", "数据库暂不可用，请检查连接后重试。"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> requestFailed(Exception error) {
    // 只记录异常类型，避免凭据、请求内容及完整 Stripe 对象进入日志。
    LOG.error("Request failed: {}", error.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(Map.of("error", "Request failed; retry with the same request key"));
  }
}

package ca.maplenorthern.cc.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error is an RFC 7807 {@code application/problem+json} body with the {@code correlationId}.
 * Framework errors (bad JSON, validation, missing parameters) keep Spring's problem details.
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> api(ApiException e, WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    pd.setTitle(e.title());
    e.properties().forEach(pd::setProperty);
    return handleExceptionInternal(e, pd, new HttpHeaders(), e.status(), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
    LOG.error("unhandled error", e);
    ProblemDetail pd =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error; quote the correlationId");
    pd.setTitle("Internal error");
    return handleExceptionInternal(
        e, pd, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail pd) {
      pd.setProperty("correlationId", MDC.get(CorrelationContext.MDC_KEY));
    }
    return response;
  }
}

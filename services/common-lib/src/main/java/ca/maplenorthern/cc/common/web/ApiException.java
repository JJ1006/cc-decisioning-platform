package ca.maplenorthern.cc.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error the API answers as RFC 7807 problem+json with the given status. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final String title;
  private final Map<String, Object> properties = new LinkedHashMap<>();

  public ApiException(HttpStatus status, String title, String detail) {
    super(detail);
    this.status = status;
    this.title = title;
  }

  /**
   * @return HTTP status of the problem
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * @return short, stable title of the problem
   */
  public String title() {
    return title;
  }

  /** Adds a member to the problem body, e.g. the list of validation errors. */
  public ApiException with(String name, Object value) {
    properties.put(name, value);
    return this;
  }

  /**
   * @return extra members of the problem body
   */
  public Map<String, Object> properties() {
    return properties;
  }
}

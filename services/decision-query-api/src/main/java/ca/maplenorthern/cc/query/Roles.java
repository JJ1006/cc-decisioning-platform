package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiException;
import ca.maplenorthern.cc.common.web.Caller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Defence in depth for the endpoints that return the most sensitive data: the gateway already
 * admits only the roles of the route, the service checks the role it was given as well. When the
 * mutual TLS filter is off (unit tests, a developer without TLS) there is no caller and nothing to
 * check.
 */
final class Roles {
  private Roles() {}

  /**
   * @return the consumer making the request, for audit records ("unknown" without a caller)
   */
  static String consumer(HttpServletRequest request) {
    return request.getAttribute(Caller.ATTR) instanceof Caller c ? c.consumer() : "unknown";
  }

  static void require(HttpServletRequest request, String... allowed) {
    if (!(request.getAttribute(Caller.ATTR) instanceof Caller caller)) {
      return;
    }
    if (!Set.of(allowed).contains(caller.role())) {
      throw new ApiException(
          HttpStatus.FORBIDDEN,
          "Role not allowed",
          "the role " + caller.role() + " may not use this endpoint");
    }
  }
}

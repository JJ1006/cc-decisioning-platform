package ca.maplenorthern.cc.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Mutual TLS identity. Tomcat (client-auth=need) has already verified the client certificate
 * against our CA; this filter lets only the listed CNs through. When the caller is the gateway (CN
 * {@code gatewayCn}), the consumer and role it authenticated arrive in X-Consumer and X-Role and
 * are trusted for exactly that reason: nobody else can present that certificate. For any other
 * allowed CN (a platform service) the consumer is the CN and the role is SERVICE; those headers are
 * ignored.
 */
public class ClientCertFilter implements Filter {
  private static final Logger LOG = LoggerFactory.getLogger(ClientCertFilter.class);
  static final String CERT_ATTR = "jakarta.servlet.request.X509Certificate";

  private final Set<String> allowedCns;
  private final String gatewayCn;

  public ClientCertFilter(Set<String> allowedCns, String gatewayCn) {
    this.allowedCns = allowedCns;
    this.gatewayCn = gatewayCn;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    if (request
        .getRequestURI()
        .startsWith(
            "/actuator")) { // only reachable on the main port when no separate management port is
      // set
      chain.doFilter(req, res);
      return;
    }
    X509Certificate[] certs = (X509Certificate[]) request.getAttribute(CERT_ATTR);
    if (certs == null || certs.length == 0) {
      deny(response, 401, "Client certificate required", "no client certificate was presented");
      return;
    }
    String cn = commonName(certs[0]);
    if (cn == null || !allowedCns.contains(cn)) {
      LOG.warn("client certificate CN {} is not allowed", cn);
      deny(
          response, 403, "Caller not allowed", "this client certificate may not call this service");
      return;
    }
    Caller caller;
    if (cn.equals(gatewayCn)) {
      String consumer = request.getHeader("X-Consumer");
      String role = request.getHeader("X-Role");
      if (consumer == null || consumer.isBlank() || role == null || role.isBlank()) {
        deny(
            response, 403, "Identity missing", "the gateway did not forward the consumer and role");
        return;
      }
      caller = new Caller(cn, consumer, role);
    } else {
      caller = new Caller(cn, cn, Caller.SERVICE);
    }
    request.setAttribute(Caller.ATTR, caller);
    chain.doFilter(req, res);
  }

  /**
   * @return the CN of the certificate subject, or null
   */
  static String commonName(X509Certificate cert) {
    try {
      for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return String.valueOf(rdn.getValue());
        }
      }
    } catch (InvalidNameException e) {
      return null;
    }
    return null;
  }

  private static void deny(HttpServletResponse response, int status, String title, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    String correlationId = MDC.get(CorrelationContext.MDC_KEY);
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + title
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\",\"correlationId\":\""
                + correlationId
                + "\"}");
  }
}

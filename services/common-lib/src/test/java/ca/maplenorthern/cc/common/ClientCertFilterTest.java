package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.maplenorthern.cc.common.web.Caller;
import ca.maplenorthern.cc.common.web.ClientCertFilter;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Who may call, from the client certificate; the gateway's X-Role and X-Consumer count only with
 * its certificate.
 */
class ClientCertFilterTest {
  private final ClientCertFilter filter =
      new ClientCertFilter(Set.of("apisix", "application-api"), "apisix");

  private static MockHttpServletRequest request(String subject) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/v1/decisions/A1");
    if (subject != null) {
      X509Certificate cert = mock(X509Certificate.class);
      when(cert.getSubjectX500Principal()).thenReturn(new X500Principal(subject));
      req.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {cert});
    }
    return req;
  }

  private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, new MockFilterChain());
    return res;
  }

  @Test
  void noCertificateIs401() throws Exception {
    var res = run(request(null));
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentType()).startsWith("application/problem+json");
  }

  @Test
  void certificateOfAnotherCnIs403EvenWithGatewayHeaders() throws Exception {
    var req = request("CN=demo-partner,O=Maple Northern Bank (demo)");
    req.addHeader("X-Role", "UNDERWRITER");
    req.addHeader("X-Consumer", "underwriter-portal");
    var res = run(req);
    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(req.getAttribute(Caller.ATTR)).isNull();
  }

  @Test
  void gatewayCertificateCarriesConsumerAndRole() throws Exception {
    var req = request("CN=apisix,O=Maple Northern Bank (demo)");
    req.addHeader("X-Role", "UNDERWRITER");
    req.addHeader("X-Consumer", "underwriter-portal");
    var res = run(req);
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(req.getAttribute(Caller.ATTR))
        .isEqualTo(new Caller("apisix", "underwriter-portal", "UNDERWRITER"));
  }

  @Test
  void gatewayWithoutIdentityHeadersIs403() throws Exception {
    assertThat(run(request("CN=apisix,O=x")).getStatus()).isEqualTo(403);
  }

  @Test
  void aServiceIsItsOwnConsumerAndIgnoresRoleHeaders() throws Exception {
    var req = request("O=x,CN=application-api");
    req.addHeader("X-Role", "UNDERWRITER");
    req.addHeader("X-Consumer", "someone-else");
    assertThat(run(req).getStatus()).isEqualTo(200);
    assertThat(req.getAttribute(Caller.ATTR))
        .isEqualTo(new Caller("application-api", "application-api", Caller.SERVICE));
  }

  @Test
  void actuatorPassesWithoutCertificate() throws Exception {
    var req = new MockHttpServletRequest("GET", "/actuator/health");
    assertThat(run(req).getStatus()).isEqualTo(200);
  }
}

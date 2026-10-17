package ca.maplenorthern.cc.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where and how to reach the ODM Rule Execution Server (property prefix {@code res}). */
@ConfigurationProperties(prefix = "res")
public record ResProperties(
    String baseUrl,
    String path,
    String username,
    String password,
    String truststore,
    String truststorePassword,
    String truststoreType,
    int connectTimeoutMs,
    int readTimeoutMs,
    int retryAttempts,
    long retryWaitMs,
    String rulesetVersion) {

  public ResProperties {
    if (path == null || path.isBlank()) {
      path = "/DecisionService/rest/ccApprovalApp/1.0/ccApproval/1.0";
    }
    if (truststoreType == null || truststoreType.isBlank()) {
      truststoreType = "PKCS12";
    }
    connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 1000;
    readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 3000;
    retryAttempts = retryAttempts > 0 ? retryAttempts : 3;
    retryWaitMs = retryWaitMs > 0 ? retryWaitMs : 200;
    if (rulesetVersion == null || rulesetVersion.isBlank()) {
      rulesetVersion = "1.0";
    }
  }
}

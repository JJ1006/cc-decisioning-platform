package ca.maplenorthern.cc.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Elasticsearch connection (property prefix {@code es}). */
@ConfigurationProperties(prefix = "es")
public record EsProperties(
    String url,
    String username,
    String password,
    String truststore,
    String truststorePassword,
    String truststoreType,
    int timeoutMs) {

  public EsProperties {
    truststoreType = truststoreType == null || truststoreType.isBlank() ? "PKCS12" : truststoreType;
    timeoutMs = timeoutMs > 0 ? timeoutMs : 10_000;
  }
}

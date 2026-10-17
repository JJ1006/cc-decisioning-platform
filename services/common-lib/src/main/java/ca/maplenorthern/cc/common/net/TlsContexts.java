package ca.maplenorthern.cc.common.net;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS client contexts built from the private CA truststore ({@code scripts/pki.sh}), as PKCS12 or
 * PEM.
 */
public final class TlsContexts {
  private TlsContexts() {}

  /**
   * @return a context that trusts only the CA in the given store
   */
  public static SSLContext trusting(String truststorePath, String password, String type) {
    try (InputStream in = Files.newInputStream(Path.of(truststorePath))) {
      KeyStore ks;
      if (truststorePath.endsWith(".crt") || truststorePath.endsWith(".pem")) {
        // a single PEM certificate (the CA) instead of a keystore
        ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        var cert =
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        ks.setCertificateEntry("ca", cert);
      } else {
        ks = KeyStore.getInstance(type);
        ks.load(in, password.toCharArray());
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, tmf.getTrustManagers(), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException("cannot load truststore " + truststorePath, e);
    }
  }

  /**
   * @return a context that presents the client certificate in {@code keystorePath} (PKCS12) and
   *     trusts only the CA in {@code truststorePath}
   */
  public static SSLContext mutual(
      String keystorePath,
      String keystorePassword,
      String truststorePath,
      String truststorePassword) {
    try {
      KeyStore keys = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
        keys.load(in, keystorePassword.toCharArray());
      }
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keys, keystorePassword.toCharArray());
      KeyStore trust = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(Path.of(truststorePath))) {
        trust.load(in, truststorePassword.toCharArray());
      }
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trust);
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      return ctx;
    } catch (Exception e) {
      throw new IllegalStateException(
          "cannot build the mutual TLS context from " + keystorePath, e);
    }
  }
}

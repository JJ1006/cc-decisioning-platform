package ca.maplenorthern.cc.common.web;

/**
 * Who is calling, proven by the client certificate.
 *
 * @param certificateCn CN of the client certificate (the TLS peer)
 * @param consumer the API consumer: for the gateway the consumer it authenticated (X-Consumer), for
 *     a service its own CN
 * @param role the consumer's role: for the gateway the role it assigned (X-Role), SERVICE for a
 *     service
 */
public record Caller(String certificateCn, String consumer, String role) {
  /**
   * Request attribute holding the {@link Caller} once {@link ClientCertFilter} accepted the
   * request.
   */
  public static final String ATTR = "platform.caller";

  /** Role of a platform service calling another one. */
  public static final String SERVICE = "SERVICE";
}

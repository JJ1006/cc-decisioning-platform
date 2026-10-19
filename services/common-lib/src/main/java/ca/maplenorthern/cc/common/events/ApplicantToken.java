package ca.maplenorthern.cc.common.events;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pseudonym of an applicant: HMAC-SHA-256 of the applicant id, first 128 bits as hex. Stable for
 * the same key (so an erasure or an access request can find the applicant), not reversible without
 * it.
 */
public final class ApplicantToken {
  private ApplicantToken() {}

  /**
   * @param key the secret (APPLICANT_TOKEN_KEY)
   * @param applicantId the applicant id of the submitted application
   * @return 32 hex characters
   */
  public static String of(String key, String applicantId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] d = mac.doFinal(applicantId.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(32);
      for (int i = 0; i < 16; i++) {
        sb.append(String.format("%02x", d[i]));
      }
      return sb.toString();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC unavailable", e);
    }
  }
}

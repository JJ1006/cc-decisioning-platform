package ca.maplenorthern.cc.notification;

import ca.maplenorthern.cc.xom.ReasonCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The adverse-action notice as a template per official language (resources/notices), with the
 * French reason texts. Text is written by people, not generated at run time: no machine
 * translation. {{name}} placeholders are replaced; the bureau name and the contact lines are
 * placeholders the bank fills in through configuration (this bank is fictitious).
 */
public final class NoticeTemplates {
  private static final String EN = read("adverse-action.en.txt");
  private static final String FR = read("adverse-action.fr.txt");
  private static final Properties REASONS_FR = readProperties("reasons.fr.properties");

  private NoticeTemplates() {}

  /** Values the bank configures; placeholders until it does. */
  public record Settings(String bureauName, String bureauContact, String bankContact) {
    public static final Settings DEMO =
        new Settings(
            "[credit bureau name]",
            "[credit bureau contact]",
            "[Maple Northern Bank contact line]");
  }

  /**
   * @return the French text of a reason, or null when there is none
   */
  public static String reasonFr(ReasonCode code) {
    return REASONS_FR.getProperty(code.name());
  }

  /**
   * @return the bilingual adverse-action body: English, a rule line, then French
   */
  public static String adverseAction(
      String applicationId, List<ReasonCode> reasons, Settings settings) {
    List<ReasonCode> principal =
        reasons == null
            ? List.of()
            : reasons.stream().filter(c -> c != ReasonCode.R_APPROVED_STANDARD).toList();
    String en =
        principal.stream()
            .map(c -> "- " + c.getDescription())
            .reduce("", (a, b) -> a + b + "\n")
            .stripTrailing();
    String fr =
        principal.stream()
            .map(c -> "- " + reasonFr(c))
            .reduce("", (a, b) -> a + b + "\n")
            .stripTrailing();
    return fill(
            EN,
            Map.of(
                "applicationId", applicationId,
                "reasons", en,
                "signature", "Maple Northern Bank (demo, fictitious)"),
            settings)
        + "\n\n----------------------------------------\n\n"
        + fill(
            FR,
            Map.of(
                "applicationId", applicationId,
                "reasons", fr,
                "signatureFr", "Banque Nord-Érable (démo, fictive)"),
            settings);
  }

  private static String fill(String template, Map<String, String> values, Settings s) {
    String out =
        template
            .replace("{{bureauName}}", s.bureauName())
            .replace("{{bureauContact}}", s.bureauContact())
            .replace("{{bankContact}}", s.bankContact());
    for (var e : values.entrySet()) {
      out = out.replace("{{" + e.getKey() + "}}", e.getValue());
    }
    return out.stripTrailing();
  }

  private static String read(String name) {
    try (InputStream in = NoticeTemplates.class.getResourceAsStream("/notices/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("missing notice template " + name, e);
    }
  }

  private static Properties readProperties(String name) {
    try (InputStream in = NoticeTemplates.class.getResourceAsStream("/notices/" + name)) {
      Properties p = new Properties();
      p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      return p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

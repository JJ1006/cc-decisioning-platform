package ca.maplenorthern.cc.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.common.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

  @Test
  void masksEveryKindOfPersonalDataAnywhereInTheTree() throws Exception {
    JsonNode in =
        Json.mapper()
            .readTree(
                """
                {"application":{"applicant":{"firstName":"Avery","lastName":"tremblay",
                  "dateOfBirth":"1990-05-14","sin":"046454286","applicantId":"CUS-1"},
                 "address":{"line1":"100 Queen St W","city":"Toronto","province":"ON","postalCode":"m5v 2t6"},
                 "employment":{"employer":"Example Corp","annualIncome":85000}},
                 "list":[{"sin":"1"},{"postalCode":"K1A0B1"}]}
                """);
    JsonNode out = PiiMasker.mask(in, TODAY);
    String text = out.toString();
    assertThat(text)
        .doesNotContain(
            "046454286", "Avery", "tremblay", "1990-05-14", "100 Queen", "M5V 2T6", "Example Corp");
    assertThat(out.at("/application/applicant/firstInitial").asText()).isEqualTo("A.");
    assertThat(out.at("/application/applicant/lastInitial").asText()).isEqualTo("T.");
    assertThat(out.at("/application/applicant/ageBand").asText()).isEqualTo("35-44");
    assertThat(out.at("/application/applicant/applicantId").asText()).isEqualTo("CUS-1");
    assertThat(out.at("/application/address/fsa").asText()).isEqualTo("M5V");
    assertThat(out.at("/application/address/city").asText()).isEqualTo("Toronto");
    assertThat(out.at("/application/employment/annualIncome").asInt()).isEqualTo(85000);
    assertThat(out.at("/list/0").has("sin")).isFalse();
    assertThat(out.at("/list/1/fsa").asText()).isEqualTo("K1A");
  }

  @Test
  void doesNotModifyItsInput() throws Exception {
    JsonNode in = Json.mapper().readTree("{\"sin\":\"1\"}");
    PiiMasker.mask(in, TODAY);
    assertThat(in.has("sin")).isTrue();
  }

  @Test
  void anUnparseableBirthDateIsStillRemoved() throws Exception {
    JsonNode out =
        PiiMasker.mask(Json.mapper().readTree("{\"dateOfBirth\":\"not-a-date\"}"), TODAY);
    assertThat(out.has("dateOfBirth")).isFalse();
    assertThat(out.get("ageBand").asText()).isEqualTo("unknown");
  }
}

package ca.maplenorthern.cc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.maplenorthern.cc.application.EventPublisher.Out;
import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.events.AuditPayload;
import ca.maplenorthern.cc.common.events.EventTypes;
import ca.maplenorthern.cc.common.events.ManualDecisionPayload;
import ca.maplenorthern.cc.common.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The HTTP behaviour with Kafka and decision-query-api replaced by mocks. */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationApiTest {
  @Autowired MockMvc mvc;
  @MockitoBean EventPublisher publisher;
  @MockitoBean QueryClient query;

  ObjectNode application;

  @BeforeEach
  void sample() throws Exception {
    Mockito.reset(publisher, query);
    JsonNode example =
        Json.mapper()
            .readTree(
                Files.readString(
                    Path.of(
                        System.getProperty("repo.root"),
                        "contracts/examples/application-submitted.example.json")));
    application = (ObjectNode) example.path("payload").path("application").deepCopy();
    application.put("applicationId", "APP-" + UUID.randomUUID());
  }

  ResultActions submit(JsonNode body, String key) throws Exception {
    MockHttpServletRequestBuilder req =
        post("/v1/applications").contentType(MediaType.APPLICATION_JSON).content(body.toString());
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    return mvc.perform(req);
  }

  JsonNode decision(String outcome, String source) throws Exception {
    return Json.mapper()
        .readTree(
            "{\"applicationId\":\"X\",\"decisionId\":\""
                + UUID.randomUUID()
                + "\",\"outcome\":\""
                + outcome
                + "\",\"decisionSource\":\""
                + source
                + "\",\"creditLimit\":2500,\"product\":\"NO_FEE\",\"decidedAt\":\"2026-10-17T14:30:00Z\"}");
  }

  // ------------------------------------------------------------------ submit

  @Test
  void validApplicationIsAcceptedWithLocationAndPublished() throws Exception {
    String id = application.get("applicationId").asText();
    submit(application, null)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/v1/applications/" + id + "/status"))
        .andExpect(header().exists("X-Correlation-Id"))
        .andExpect(jsonPath("$.applicationId").value(id))
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Out>> events = ArgumentCaptor.forClass(List.class);
    verify(publisher).publish(eq(id), events.capture());
    assertThat(events.getValue()).hasSize(1);
    assertThat(events.getValue().get(0).topic()).isEqualTo("cc.applications.submitted.v1");
    assertThat(events.getValue().get(0).event().eventType()).isEqualTo(EventTypes.SUBMITTED);
  }

  @Test
  void missingApplicationIdIsAssignedByTheServer() throws Exception {
    application.remove("applicationId");
    submit(application, null)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.applicationId").value(org.hamcrest.Matchers.startsWith("APP-")));
  }

  @Test
  void applicationThatBreaksTheContractIsAProblemWithTheErrors() throws Exception {
    application.remove("bureau");
    application.put("channel", "FAX");
    submit(application, null)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid application"))
        .andExpect(
            jsonPath("$.errors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.correlationId").exists());
    verify(publisher, never()).publish(any(), any());
  }

  @Test
  void bodyThatIsNotAnObjectIsRejected() throws Exception {
    mvc.perform(post("/v1/applications").contentType(MediaType.APPLICATION_JSON).content("[1]"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/v1/applications").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void sameIdempotencyKeyAndBodyReplaysWithoutPublishingTwice() throws Exception {
    String key = UUID.randomUUID().toString();
    String id = application.get("applicationId").asText();
    submit(application, key).andExpect(status().isAccepted());
    submit(application, key)
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.applicationId").value(id));
    verify(publisher, times(1)).publish(any(), any());
  }

  @Test
  void sameIdempotencyKeyWithAnotherBodyIsUnprocessable() throws Exception {
    String key = UUID.randomUUID().toString();
    submit(application, key).andExpect(status().isAccepted());
    application.put("requestedLimit", 9999);
    submit(application, key)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Idempotency-Key reused"));
  }

  @Test
  void overlongIdempotencyKeyIsRejected() throws Exception {
    submit(application, "k".repeat(129)).andExpect(status().isBadRequest());
  }

  @Test
  void kafkaOutageIs503() throws Exception {
    Mockito.doThrow(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Event bus unavailable", "down"))
        .when(publisher)
        .publish(any(), any());
    submit(application, null)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Event bus unavailable"));
  }

  // ------------------------------------------------------------------ status

  @Test
  void statusFollowsTheLatestDecision() throws Exception {
    when(query.latestDecision("A1")).thenReturn(Optional.of(decision("REFER", "ODM")));
    mvc.perform(get("/v1/applications/A1/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REFERRED"))
        .andExpect(jsonPath("$.decisionSource").value("ODM"));
    when(query.latestDecision("A2")).thenReturn(Optional.of(decision("APPROVE", "MANUAL")));
    mvc.perform(get("/v1/applications/A2/status"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decisionSource").value("MANUAL"))
        .andExpect(jsonPath("$.creditLimit").value(2500));
  }

  @Test
  void acceptedButNotYetDecidedIsSubmittedAndUnknownIs404() throws Exception {
    String id = application.get("applicationId").asText();
    submit(application, null).andExpect(status().isAccepted());
    when(query.latestDecision(any())).thenReturn(Optional.empty());
    mvc.perform(get("/v1/applications/" + id + "/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
    mvc.perform(get("/v1/applications/NOPE/status"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Application not found"));
  }

  @Test
  void statusWhenTheQueryApiIsDownIs503() throws Exception {
    when(query.latestDecision(any()))
        .thenThrow(
            new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Decision query unavailable", "down"));
    mvc.perform(get("/v1/applications/A1/status")).andExpect(status().isServiceUnavailable());
  }

  // ------------------------------------------------------------------ manual decision

  String approve() {
    return "{\"outcome\":\"APPROVE\",\"creditLimit\":3000,\"product\":\"NO_FEE\",\"reason\":\"Verified income by phone\",\"underwriterId\":\"UW-77\"}";
  }

  ResultActions manual(String id, String role, String key, String body) throws Exception {
    MockHttpServletRequestBuilder req =
        post("/v1/applications/" + id + "/manual-decision")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (role != null) {
      req = req.header("X-Role", role);
    }
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    return mvc.perform(req);
  }

  @Test
  void underwriterApprovesAReferralAndBothEventsArePublished() throws Exception {
    JsonNode referred = decision("REFER", "ODM");
    when(query.latestDecision("R1")).thenReturn(Optional.of(referred));
    manual("R1", "UNDERWRITER", null, approve())
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decisionSource").value("MANUAL"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Out>> events = ArgumentCaptor.forClass(List.class);
    verify(publisher).publish(eq("R1"), events.capture());
    assertThat(events.getValue())
        .extracting(Out::topic)
        .containsExactly("cc.decisions.manual.v1", "cc.audit.v1");
    var m = (ManualDecisionPayload) events.getValue().get(0).event().payload();
    assertThat(m.underwriterId()).isEqualTo("UW-77");
    assertThat(m.originalDecisionEventId().toString())
        .isEqualTo(referred.path("decisionId").asText());
    assertThat(m.note()).isEqualTo("Verified income by phone");
    var a = (AuditPayload) events.getValue().get(1).event().payload();
    assertThat(a.action()).isEqualTo("MANUAL_DECISION");
    assertThat(a.result()).isEqualTo(AuditPayload.Result.SUCCESS);
    assertThat(a.actor()).isEqualTo("UW-77");
  }

  @Test
  void missingRoleIs401AndOtherRoleIs403AndAudited() throws Exception {
    manual("R1", null, null, approve()).andExpect(status().isUnauthorized());
    manual("R1", "PARTNER", null, approve())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.title").value("Role not allowed"));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Out>> events = ArgumentCaptor.forClass(List.class);
    verify(publisher, times(1)).publish(eq("R1"), events.capture()); // only the denial audit
    var a = (AuditPayload) events.getValue().get(0).event().payload();
    assertThat(a.result()).isEqualTo(AuditPayload.Result.DENIED);
    assertThat(a.actor()).isEqualTo("role:PARTNER");
    verify(query, never()).latestDecision(any());
  }

  @Test
  void onlyReferredApplicationsCanBeDecidedManually() throws Exception {
    when(query.latestDecision("D1")).thenReturn(Optional.of(decision("DECLINE", "ODM")));
    manual("D1", "UNDERWRITER", null, approve())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Not a referral"));
    when(query.latestDecision("D2")).thenReturn(Optional.of(decision("APPROVE", "MANUAL")));
    manual("D2", "UNDERWRITER", null, approve()).andExpect(status().isConflict());
    when(query.latestDecision("D3")).thenReturn(Optional.empty());
    manual("D3", "UNDERWRITER", null, approve()).andExpect(status().isNotFound());
    verify(publisher, never()).publish(any(), any());
  }

  @Test
  void manualDecisionBodyRules() throws Exception {
    String blankReason = approve().replace("Verified income by phone", " ");
    manual("R1", "UNDERWRITER", null, blankReason).andExpect(status().isBadRequest());
    manual("R1", "UNDERWRITER", null, approve().replace(",\"product\":\"NO_FEE\"", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0]").value("product is required for APPROVE"));
    manual("R1", "UNDERWRITER", null, approve().replace("\"APPROVE\"", "\"DECLINE\""))
        .andExpect(status().isBadRequest()); // DECLINE with a limit and a product
    manual("R1", "UNDERWRITER", null, approve().replace("\"APPROVE\"", "\"REFER\""))
        .andExpect(status().isBadRequest());
    manual("R1", "UNDERWRITER", null, approve().replace("3000", "-1"))
        .andExpect(status().isBadRequest());
    manual("R1", "UNDERWRITER", null, "{}").andExpect(status().isBadRequest());
    verify(publisher, never()).publish(any(), any());
  }

  @Test
  void declineNeedsZeroLimitAndNoProduct() throws Exception {
    when(query.latestDecision("R2")).thenReturn(Optional.of(decision("REFER", "ODM")));
    manual(
            "R2",
            "UNDERWRITER",
            null,
            "{\"outcome\":\"DECLINE\",\"creditLimit\":0,\"reason\":\"Income not verifiable\",\"underwriterId\":\"UW-77\"}")
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("DECLINED"));
  }

  @Test
  void manualDecisionIsIdempotent() throws Exception {
    when(query.latestDecision("R3")).thenReturn(Optional.of(decision("REFER", "ODM")));
    String key = UUID.randomUUID().toString();
    manual("R3", "UNDERWRITER", key, approve()).andExpect(status().isAccepted());
    manual("R3", "UNDERWRITER", key, approve())
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotent-Replayed", "true"));
    verify(publisher, times(1)).publish(eq("R3"), any());
  }
}

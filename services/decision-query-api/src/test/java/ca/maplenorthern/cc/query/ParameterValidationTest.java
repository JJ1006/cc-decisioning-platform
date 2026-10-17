package ca.maplenorthern.cc.query;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.maplenorthern.cc.common.web.ApiSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller behaviour without Elasticsearch: bad input is a 400 problem, a store failure a 503.
 */
@WebMvcTest({DecisionsController.class, StatsController.class, OpsController.class})
@Import(ApiSupport.class)
class ParameterValidationTest {
  @Autowired MockMvc mvc;
  @MockitoBean DecisionStore store;

  @Test
  void sizeAboveTheMaximumIsRejected() throws Exception {
    mvc.perform(get("/v1/decisions?size=101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("size must be 1 to 100"));
  }

  @Test
  void minScoreAboveMaxScoreIsRejected() throws Exception {
    mvc.perform(get("/v1/decisions?minScore=700&maxScore=600")).andExpect(status().isBadRequest());
  }

  @Test
  void blankCorrelationIdIsRejected() throws Exception {
    mvc.perform(get("/v1/ops/logs").param("correlationId", " ")).andExpect(status().isBadRequest());
  }

  @Test
  void missingRequiredParameterIsAProblem() throws Exception {
    mvc.perform(get("/v1/ops/logs"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  void unexpectedErrorDoesNotLeakDetails() throws Exception {
    org.mockito.Mockito.when(store.latency())
        .thenThrow(new IllegalStateException("secret internals"));
    mvc.perform(get("/v1/stats/latency"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Unexpected error; quote the correlationId"));
  }
}

package ca.maplenorthern.cc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.maplenorthern.cc.xom.AprTier;
import ca.maplenorthern.cc.xom.Outcome;
import ca.maplenorthern.cc.xom.Product;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The business metrics of a decision: counted by outcome, product and province only, timed, breaker
 * state exposed.
 */
class DecisionMetricsTest {

  @Test
  void decisionIsCountedTimedAndLabelledWithoutAnythingPersonal() throws Exception {
    ResClient res = mock(ResClient.class);
    when(res.execute(any()))
        .thenReturn(
            new RuleDecision(
                Outcome.APPROVE,
                new BigDecimal("5000"),
                Product.CASHBACK,
                AprTier.TIER_2,
                List.of(),
                List.of("RSN-01")));
    when(res.rulesetVersion()).thenReturn("1.0");
    when(res.rulesetPath()).thenReturn("/x");
    CircuitBreaker breaker = CircuitBreaker.ofDefaults("t");
    when(res.breaker()).thenReturn(breaker);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DecisionRouter router = new DecisionRouter(res, kafka, registry);

    String event =
        Files.readString(
            Path.of(
                System.getProperty("repo.root"),
                "contracts/examples/application-submitted.example.json"));
    router.handle(event, "c-1");

    var counter =
        registry
            .get("decisions.total")
            .tag("outcome", "APPROVE")
            .tag("product", "CASHBACK")
            .tag("province", "ON")
            .counter();
    assertThat(counter.count()).isEqualTo(1.0);
    assertThat(counter.getId().getTags())
        .extracting("key")
        .containsExactlyInAnyOrder("outcome", "product", "province");
    assertThat(registry.get("res.call").timer().count()).isEqualTo(1);
    assertThat(registry.get("res.circuitbreaker.state").gauge().value()).isEqualTo(0.0);
    breaker.transitionToOpenState();
    assertThat(registry.get("res.circuitbreaker.state").gauge().value()).isEqualTo(2.0);
  }
}

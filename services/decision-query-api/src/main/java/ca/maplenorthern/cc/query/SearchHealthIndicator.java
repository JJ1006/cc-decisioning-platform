package ca.maplenorthern.cc.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch is reachable and the read-only user may search. A search, because the user has no
 * cluster privileges (Boot's own indicator calls the cluster health API and would report DOWN).
 * Part of /actuator/health, not of the readiness probe: a flapping Elasticsearch must not take the
 * pods out of service, requests answer 503 instead.
 */
@Component("search")
class SearchHealthIndicator implements HealthIndicator {
  private final ElasticsearchClient es;

  SearchHealthIndicator(ElasticsearchClient es) {
    this.es = es;
  }

  @Override
  public Health health() {
    try {
      es.search(s -> s.index("cc-decisions").size(0), Void.class);
      return Health.up().build();
    } catch (Exception e) {
      return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
    }
  }
}

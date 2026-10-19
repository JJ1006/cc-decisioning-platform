package ca.maplenorthern.cc.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import org.elasticsearch.client.RestClient;

/**
 * The Elasticsearch client of the privacy-ops user; {@code client} is null when it is not
 * configured.
 */
record PrivacyEs(RestClient rest, ElasticsearchClient client) implements AutoCloseable {
  boolean configured() {
    return client != null;
  }

  @Override
  public void close() throws IOException {
    if (rest != null) {
      rest.close();
    }
  }
}

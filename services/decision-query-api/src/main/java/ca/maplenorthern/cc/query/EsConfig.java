package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.Json;
import ca.maplenorthern.cc.common.net.TlsContexts;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Elasticsearch Java client over TLS (private CA) as the read-only user decision-query-api. */
@Configuration(proxyBeanMethods = false)
public class EsConfig {

  @Bean(destroyMethod = "close")
  RestClient restClient(
      @Value("${es.url}") String url,
      @Value("${es.ca:}") String ca,
      @Value("${es.username:}") String username,
      @Value("${es.password:}") String password) {
    var builder = RestClient.builder(HttpHost.create(url));
    builder.setHttpClientConfigCallback(
        http -> {
          if (!ca.isBlank()) {
            http.setSSLContext(TlsContexts.trusting(ca, "", "PKCS12"));
          }
          if (!username.isBlank()) {
            CredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(
                AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            http.setDefaultCredentialsProvider(creds);
          }
          return http;
        });
    return builder.build();
  }

  @Bean
  ElasticsearchClient elasticsearchClient(RestClient restClient) {
    return new ElasticsearchClient(
        new RestClientTransport(restClient, new JacksonJsonpMapper(Json.mapper())));
  }
}

package ca.maplenorthern.cc.query;

import ca.maplenorthern.cc.common.web.ApiSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Read-only API over the PII-masked Elasticsearch indexes. The only service that reads them. */
@SpringBootApplication
@Import(ApiSupport.class)
public class QueryApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(QueryApiApplication.class, args);
  }
}

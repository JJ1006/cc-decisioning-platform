package ca.maplenorthern.cc.query;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Maple Northern Bank decision query API")
                .version("1.0.0")
                .description(
                    "Read-only decisions, explanations, statistics, fairness, dead letters and logs."
                        + " Fictitious bank, synthetic data. Errors are RFC 7807 problem+json.")
                .contact(
                    new Contact().name("Platform team").email("platform@maplenorthern.example"))
                .license(
                    new License()
                        .name("Apache-2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")))
        .addTagsItem(new Tag().name("Decisions").description("Decisions and their explanations"))
        .addTagsItem(
            new Tag()
                .name("Statistics")
                .description("Approval rates, decline reasons, fairness and latency"))
        .addTagsItem(
            new Tag().name("Operations").description("Dead letters and logs for operators"))
        .addServersItem(
            new Server().url("http://localhost:8085").description("local compose stack"));
  }

  /**
   * Every operation can answer with an RFC 7807 problem: document it once as the default response.
   */
  @Bean
  OpenApiCustomizer problemResponses() {
    return openApi -> {
      var problem =
          new ObjectSchema()
              .description("RFC 7807 problem details")
              .addProperty("type", new StringSchema().example("about:blank"))
              .addProperty("title", new StringSchema().example("Invalid parameter"))
              .addProperty("status", new IntegerSchema().example(400))
              .addProperty("detail", new StringSchema().example("size must be 1 to 100"))
              .addProperty("instance", new StringSchema().example("/v1/decisions"))
              .addProperty(
                  "correlationId",
                  new StringSchema().description("Quote it when reporting a problem"))
              .addProperty(
                  "errors",
                  new ArraySchema()
                      .items(new StringSchema())
                      .description("Validation messages, when there are several"));
      openApi.getComponents().addSchemas("Problem", problem);
      openApi
          .getPaths()
          .values()
          .forEach(
              path ->
                  path.readOperations()
                      .forEach(
                          op ->
                              op.getResponses()
                                  .addApiResponse(
                                      "default",
                                      new ApiResponse()
                                          .description("Problem")
                                          .content(
                                              new Content()
                                                  .addMediaType(
                                                      "application/problem+json",
                                                      new MediaType()
                                                          .schema(
                                                              new Schema<>()
                                                                  .$ref(
                                                                      "#/components/schemas/Problem")))))));
    };
  }
}

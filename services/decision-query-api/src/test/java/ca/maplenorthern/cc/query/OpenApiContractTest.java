package ca.maplenorthern.cc.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The committed docs/api/decision-query-api.yaml is the generated one. Regenerate with {@code make
 * api-docs} (sets -Dopenapi.write=true); CI fails on drift.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {
  @Autowired MockMvc mvc;

  @Test
  void committedSpecIsUpToDate() throws Exception {
    String generated =
        mvc.perform(get("/v3/api-docs.yaml")).andReturn().getResponse().getContentAsString().strip()
            + "\n";
    Path file = Path.of(System.getProperty("repo.root"), "docs/api/decision-query-api.yaml");
    if (Boolean.getBoolean("openapi.write")) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, generated);
    }
    assertThat(file).as("run make api-docs").exists();
    assertThat(Files.readString(file)).as("run make api-docs to regenerate").isEqualTo(generated);
  }
}

package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TopicsTest {
  @Test
  void retryAndDlqNamesDeriveFromTopic() {
    assertThat(Topics.retry(Topics.SUBMITTED)).isEqualTo("cc.applications.submitted.v1.retry");
    assertThat(Topics.dlq(Topics.SUBMITTED)).isEqualTo("cc.applications.submitted.v1.dlq");
  }

  @Test
  void businessTopicsAreVersionedAndUnique() {
    assertThat(Topics.BUSINESS).hasSize(7).doesNotHaveDuplicates().allMatch(t -> t.endsWith(".v1"));
  }
}

package ca.maplenorthern.cc.common;

import static org.assertj.core.api.Assertions.assertThat;

import ca.maplenorthern.cc.common.web.CorrelationContext;
import ca.maplenorthern.cc.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void reusesIncomingIdAndClearsMdcAfterwards() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader(CorrelationContext.HEADER, "abc-123");
    MockHttpServletResponse res = new MockHttpServletResponse();
    String[] seenInChain = new String[1];

    filter.doFilter(
        req,
        res,
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
            seenInChain[0] = MDC.get(CorrelationContext.MDC_KEY);
          }
        });

    assertThat(seenInChain[0]).isEqualTo("abc-123");
    assertThat(res.getHeader(CorrelationContext.HEADER)).isEqualTo("abc-123");
    assertThat(MDC.get(CorrelationContext.MDC_KEY)).isNull();
  }

  @Test
  void generatesIdWhenAbsent() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest(), res, new MockFilterChain());
    assertThat(res.getHeader(CorrelationContext.HEADER)).isNotBlank();
  }
}

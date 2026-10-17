package ca.maplenorthern.cc.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Reads or creates X-Correlation-Id, exposes it in the MDC and echoes it on the response. */
public class CorrelationIdFilter implements Filter {

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String id =
        CorrelationContext.set(((HttpServletRequest) req).getHeader(CorrelationContext.HEADER));
    ((HttpServletResponse) res).setHeader(CorrelationContext.HEADER, id);
    try {
      chain.doFilter(req, res);
    } finally {
      CorrelationContext.clear();
    }
  }
}

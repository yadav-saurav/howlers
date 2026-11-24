package app.howlers.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlation id per request: honours a well-formed inbound X-Trace-Id, otherwise generates one. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String inbound = req.getHeader(HEADER);
        String traceId = inbound != null && SAFE.matcher(inbound).matches()
                ? inbound : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

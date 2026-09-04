package com.heapy.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String TRACE_ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(REQUEST_ID_HEADER, traceId);
        MDC.put(MDC_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    public static String getTraceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(TRACE_ID_ATTRIBUTE);
        return traceId == null ? UUID.randomUUID().toString() : traceId.toString();
    }

    private String resolveTraceId(String requestedId) {
        if (requestedId == null || requestedId.isBlank() || requestedId.length() > 128) {
            return UUID.randomUUID().toString();
        }
        return requestedId;
    }
}

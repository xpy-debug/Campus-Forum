package com.school.forum.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求生成 traceId 并写入日志上下文（MDC）。
 *
 * <p>这样一次请求产生的所有日志都带同一个 traceId，
 * 排查问题时用 {@code grep traceId} 就能把散落在各处的日志串成一条完整链路。
 * 响应体里也会带上它，用户报障时直接提供这个 ID 即可精确定位。
 *
 * <p><b>必须 {@code finally} 里清理 MDC：</b>Tomcat 的工作线程是复用的，
 * 不清理的话下一个请求会继承上一个请求的 traceId，
 * 日志会莫名其妙地串在一起，比没有 traceId 更糟。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 优先用上游（网关、其他服务）传下来的 traceId，保证跨服务链路不断开
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}

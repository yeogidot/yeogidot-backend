package com.yeogidot.yeogidot.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class UploadTimingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod())
                || !("/api/photos/upload".equals(path) || "/api/travels".equals(path));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String id = UUID.randomUUID().toString(); // Never reflect arbitrary client header values into logs.
        String operation = request.getRequestURI().endsWith("/api/photos/upload") ? "photo_upload" : "travel_create";
        response.setHeader(REQUEST_ID_HEADER, id);
        long started = System.nanoTime();
        boolean returned = false;
        try (var ignored = UploadTiming.bind(new UploadTiming.Context(id, operation, 0))) {
            try {
                chain.doFilter(request, response);
                returned = true;
            } finally {
                // Synchronous filter-chain elapsed time; not client round-trip or socket delivery time.
                UploadTiming.elapsed("http_request", started,
                        returned ? "HTTP_" + response.getStatus() : "UNHANDLED_ERROR");
            }
        }
    }
}

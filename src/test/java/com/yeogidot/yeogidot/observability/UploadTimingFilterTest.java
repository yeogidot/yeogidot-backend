package com.yeogidot.yeogidot.observability;

import com.yeogidot.yeogidot.config.SecurityConfig;
import com.yeogidot.yeogidot.exception.JwtAccessDeniedHandler;
import com.yeogidot.yeogidot.exception.JwtAuthenticationEntryPoint;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UploadTimingFilterTest {
    private final UploadTimingFilter filter = new UploadTimingFilter();

    @Test void 요청별_ID를_발급하고_본문과_상태를_유지하며_민감한_입력은_기록하지_않는다() throws Exception {
        try (var logs = new TimingLogCapture()) {
            var request = new MockHttpServletRequest("POST", "/api/photos/upload");
            request.setQueryString("metadata=private-coordinate");
            request.addHeader("Authorization", "Bearer secret-token");
            request.addHeader("X-Request-ID", "untrusted-client-id");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                assertThat(UploadTiming.current().requestId()).isEqualTo(response.getHeader("X-Request-ID"));
                response.setStatus(201);
                response.getWriter().write("unchanged");
            });
            String first = response.getHeader("X-Request-ID");
            assertThat(UUID.fromString(first).toString()).isEqualTo(first);
            assertThat(response.getContentAsString()).isEqualTo("unchanged");
            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(logs.lines).singleElement().asString()
                    .contains("request_id=" + first, "stage=http_request", "outcome=HTTP_201")
                    .doesNotContain("secret-token", "private-coordinate", "untrusted-client-id");
            assertThat(UploadTiming.current()).isNull();

            var next = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/travels"), next, (req, res) -> {});
            assertThat(next.getHeader("X-Request-ID")).isNotEqualTo(first);
            assertThat(logs.lines.getLast()).contains("operation=travel_create");
        }
    }

    @Test void 처리되지_않은_예외도_시간을_남기고_스레드_문맥을_제거한다() {
        try (var logs = new TimingLogCapture()) {
            var failure = new ServletException("private-error-message");
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("POST", "/api/travels"),
                    new MockHttpServletResponse(), (req, res) -> { throw failure; })).isSameAs(failure);
            assertThat(logs.lines).singleElement().asString()
                    .contains("outcome=UNHANDLED_ERROR").doesNotContain("private-error-message");
            assertThat(UploadTiming.current()).isNull();
        }
    }

    @Test void 인증_거절은_성공으로_기록하지_않고_다른_API는_계측하지_않는다() throws Exception {
        try (var logs = new TimingLogCapture()) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/photos/upload"), response,
                    (req, res) -> response.setStatus(401));
            assertThat(logs.lines).singleElement().asString().contains("outcome=HTTP_401");
            var other = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/photos"), other, (req, res) -> {
                assertThat(UploadTiming.current()).isNull();
            });
            assertThat(other.getHeader("X-Request-ID")).isNull();
            assertThat(logs.lines).hasSize(1);
        }
    }

    @Test void 컨텍스트_경로와_중첩된_문맥을_처리한다() throws Exception {
        var outer = new UploadTiming.Context("outer", "outer", 0);
        try (var ignored = UploadTiming.bind(outer)) {
            var request = new MockHttpServletRequest("POST", "/app/api/travels");
            request.setContextPath("/app");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> assertThat(UploadTiming.current()).isNotEqualTo(outer));
            assertThat(response.getHeader("X-Request-ID")).isNotBlank();
            assertThat(UploadTiming.current()).isSameAs(outer);
        }
        assertThat(UploadTiming.current()).isNull();
    }

    @Test void 허용된_프론트에서_응답_ID를_읽을수_있도록_CORS에_명시한다() throws Exception {
        var security = new SecurityConfig(mock(JwtTokenProvider.class), mock(JwtAuthenticationEntryPoint.class),
                mock(JwtAccessDeniedHandler.class), mock(StringRedisTemplate.class), mock(UserRepository.class));
        var cors = new CorsFilter(security.corsConfigurationSource());
        var request = new MockHttpServletRequest("POST", "/api/photos/upload");
        request.addHeader("Origin", "https://yeogidot.jihongeek.com");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> cors.doFilter(req, res, (r, s) -> {}));
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Request-ID");
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://yeogidot.jihongeek.com");
        assertThat(response.getHeader("X-Request-ID")).isNotBlank();
    }
}

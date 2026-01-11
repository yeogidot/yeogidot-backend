package com.yeogidot.yeogidot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     *  JWT 검사를 하지 않을 경로 지정
     * - 로그인 / 회원가입은 JWT가 아직 없으므로 필터 제외
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        return path.startsWith("/api/auth/")      // 로그인, 회원가입
                || path.startsWith("/swagger-ui")     // Swagger UI
                || path.startsWith("/v3/api-docs");   // API Docs
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. 요청 헤더에서 JWT 토큰 추출
        String token = resolveToken(request);

        // 2. 토큰이 있고 유효하면 인증 처리
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Authentication auth = jwtTokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        // 3. 다음 필터로 진행
        filterChain.doFilter(request, response);
    }

    /**
     * Authorization 헤더에서 "Bearer " 제거 후 순수 토큰 반환
     */
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

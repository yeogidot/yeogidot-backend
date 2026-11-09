package com.yeogidot.yeogidot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;

import java.io.IOException;
import java.time.Instant;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;

    private static final String BLACKLIST_PREFIX = "blacklist:";

    /**
     * JWT 검사를 하지 않을 경로 지정
     * - /api/auth/signup, /api/auth/login, /api/auth/logout 은 토큰 불필요 → 제외
     * - /api/auth/password (PATCH), /api/auth/account (DELETE) 는 토큰 필요 → 필터 통과
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 비밀번호 변경 / 회원탈퇴는 JWT 인증 필요 → 필터에서 제외하지 않음
        if (path.equals("/api/auth/password") || path.equals("/api/auth/account")
                || path.equals("/api/auth/me") || path.equals("/api/auth/nickname")) {
            return false;
        }

        return path.startsWith("/api/auth/")      // 로그인, 회원가입, 로그아웃
                || path.startsWith("/swagger-ui")     // Swagger UI
                || path.startsWith("/v3/api-docs");   // API Docs
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String token = resolveToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Claims claims;
        try {
            claims = jwtTokenProvider.parseClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        }

        if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이미 로그아웃된 토큰입니다.");
            return;
        }

        String email;
        try {
            email = claims.getSubject();
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null) {
                Instant issuedAt = claims.getIssuedAt().toInstant();
                if (issuedAt.isBefore(user.getPasswordChangedAt())) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "비밀번호가 변경되어 재로그인이 필요합니다.");
                    return;
                }
            }
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        }

        try {
            Authentication auth = jwtTokenProvider.getAuthenticationByEmail(email);
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (UsernameNotFoundException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "존재하지 않는 사용자입니다.");
            return;
        }

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

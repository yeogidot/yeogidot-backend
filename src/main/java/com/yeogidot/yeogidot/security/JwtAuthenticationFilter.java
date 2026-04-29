package com.yeogidot.yeogidot.security;

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

import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

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
        if (path.equals("/api/auth/password") || path.equals("/api/auth/account")) {
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

        // 1. 요청 헤더에서 JWT 토큰 추출
        String token = resolveToken(request);

        // 2. 토큰이 있는데 유효하지 않으면 즉시 401 반환
        if (token != null && !jwtTokenProvider.validateToken(token)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        }

        // 3. 블랙리스트 토큰(로그아웃된 토큰) 차단
        if (token != null && Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "이미 로그아웃된 토큰입니다.");
            return;
        }

        // 4. 토큰이 유효하면 인증 처리
        //    회원탈퇴 후 만료되지 않은 토큰으로 요청 시 DB에 유저가 없어
        //    UsernameNotFoundException 발생 → 필터 레이어라 @RestControllerAdvice 미적용
        //    직접 catch해서 401 반환
        if (token != null) {
            try {
                Authentication auth = jwtTokenProvider.getAuthentication(token);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (UsernameNotFoundException e) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "존재하지 않는 사용자입니다.");
                return;
            }
        }

        // 5. 다음 필터로 진행
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

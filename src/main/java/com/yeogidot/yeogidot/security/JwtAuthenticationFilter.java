package com.yeogidot.yeogidot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 요청 헤더에서 토큰 꺼내기
        String token = resolveToken(request);

        // 2. 토큰이 있고, 유효하다면?
        if (token != null && jwtTokenProvider.validateToken(token)) {
            // 토큰에서 이메일 꺼내기
            String email = jwtTokenProvider.getEmail(token);

            // 이 사람은 인증된 사용자다(SecurityContext에 저장)
            // (권한 관리가 복잡하지 않다면 간단하게 Authentication 객체를 만들기)
            Authentication auth = jwtTokenProvider.getAuthentication(token);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        // 3. 다음 단계로 통과
        filterChain.doFilter(request, response);
    }

    // 헤더에서 "Bearer " 떼고 순수 토큰만 가져오는 메서드
    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}


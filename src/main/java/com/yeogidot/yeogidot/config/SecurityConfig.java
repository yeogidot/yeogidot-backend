package com.yeogidot.yeogidot.config;

import com.yeogidot.yeogidot.security.JwtAuthenticationFilter;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // OPTIONS 요청 (CORS preflight) 허용
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 인증 불필요 경로
                        .requestMatchers(
                                "/api/auth/**",                    // 회원가입, 로그인
                                "/api/travels/share/**",          // 여행 공유 URL 조회 (공개)
                                "/api/photos/map-markers",        // 지도 마커 조회 (공개)
                                "/swagger-ui/**",                 // Swagger UI
                                "/v3/api-docs/**"                 // API 문서
                        ).permitAll()
                        // 나머지는 인증 필요 (사진 업로드 포함)
                        .anyRequest().authenticated()
                )
                // JWT 필터 등록
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider), 
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 개발 환경: localhost 모든 포트 허용
        // 운영 환경: 실제 프론트엔드 도메인으로 변경 필요
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost",           // 포트 없는 localhost
                "http://localhost:*",         // localhost 모든 포트
                "http://127.0.0.1",           // 포트 없는 127.0.0.1
                "http://127.0.0.1:*",         // 127.0.0.1 모든 포트
                "http://34.50.29.65:*"        // GCP 서버
        ));
        
        // 모든 HTTP 메서드 허용 (OPTIONS 포함!)
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        
        // 모든 헤더 허용
        configuration.setAllowedHeaders(Arrays.asList("*"));
        
        // 노출할 헤더 설정
        configuration.setExposedHeaders(Arrays.asList("*"));
        
        // 인증 정보 포함 허용
        configuration.setAllowCredentials(true);
        
        // preflight 요청 캐시 시간 (1시간)
        configuration.setMaxAge(3600L);

        // 모든 경로에 적용
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

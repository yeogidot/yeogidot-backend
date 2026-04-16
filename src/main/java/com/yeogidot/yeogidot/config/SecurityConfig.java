package com.yeogidot.yeogidot.config;

import com.yeogidot.yeogidot.exception.JwtAccessDeniedHandler;
import com.yeogidot.yeogidot.exception.JwtAuthenticationEntryPoint;
import com.yeogidot.yeogidot.security.JwtAuthenticationFilter;
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final StringRedisTemplate redisTemplate;

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

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)  // 401 처리
                        .accessDeniedHandler(jwtAccessDeniedHandler)             // 403 처리
                )

                .authorizeHttpRequests(auth -> auth
                        // OPTIONS 요청 (CORS preflight) 허용
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // 인증 필요 - 비밀번호 변경, 회원탈퇴 (permitAll 범위에서 명시적으로 제외)
                        .requestMatchers(HttpMethod.PATCH, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/auth/account").authenticated()

                        // 인증 불필요 경로
                        .requestMatchers(
                                "/api/auth/**",                    // 회원가입, 로그인, 로그아웃
                                "/api/travels/share/**",          // 여행 공유 URL 조회 (공개)
                                "/swagger-ui/**",                 // Swagger UI
                                "/v3/api-docs/**"                 // API 문서
                        ).permitAll()

                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, redisTemplate),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "https://yeogidot.jihongeek.com",
                "https://yeogidot-frontend.jihongeek.workers.dev",
                "http://10.0.2.2:3000"
        ));

        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));

        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setExposedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

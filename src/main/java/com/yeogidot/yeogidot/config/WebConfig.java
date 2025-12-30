package com.yeogidot.yeogidot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 모든 주소(/**)에 대해, 모든 출처(Patterns "*")에서의 요청을 허용
        registry.addMapping("/**")
                .allowedOriginPatterns("*") // React(5173) 등 외부 접속 허용
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS") // 모든 HTTP 메서드 허용
                .allowCredentials(true); // 쿠키/인증 정보 포함 허용
    }
}
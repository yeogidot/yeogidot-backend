package com.yeogidot.yeogidot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// 메인 애플리케이션 클래스

@EnableJpaAuditing // JPA Auditing 활성화 (생성일/수정일 자동 기록용)
@SpringBootApplication(exclude = {
		// 1. Spring Security 자동 설정 제외 (개발 초기 단계에서 로그인 없이 API 테스트하기 위함)
		SecurityAutoConfiguration.class,
		// 2. GCP Storage 관련 자동 설정 제외 (로컬 개발 시 인증 키 에러 방지)
		com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration.class,
		com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration.class
})
public class YeogidotApplication {

	public static void main(String[] args) {
		SpringApplication.run(YeogidotApplication.class, args);
	}
}
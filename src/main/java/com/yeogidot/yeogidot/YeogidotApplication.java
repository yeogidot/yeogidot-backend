package com.yeogidot.yeogidot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// 메인 애플리케이션 클래스

@EnableJpaAuditing
@EnableCaching
@SpringBootApplication(exclude = {
		SecurityAutoConfiguration.class
})
public class YeogidotApplication {

	public static void main(String[] args) {
		SpringApplication.run(YeogidotApplication.class, args);
	}
}
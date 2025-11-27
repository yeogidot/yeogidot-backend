package com.yeogidot.yeogidot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration;
import com.google.cloud.spring.autoconfigure.core.GcpContextAutoConfiguration;

@SpringBootApplication(exclude = {
		GcpStorageAutoConfiguration.class,
		GcpContextAutoConfiguration.class,
		SecurityAutoConfiguration.class
})
public class YeogidotApplication {
	public static void main(String[] args) {
		SpringApplication.run(YeogidotApplication.class, args);
	}
}
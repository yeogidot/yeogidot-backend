package com.yeogidot.yeogidot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import javax.sql.DataSource;
import org.springframework.context.ApplicationContext;
import com.yeogidot.yeogidot.service.R2DeletionScheduler;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

// Never load developer/production application*.properties in this smoke test.
@SpringBootTest(properties = "spring.config.location=classpath:context-test.properties")
class YeogidotApplicationTests {

	@MockitoBean S3Client s3Client;
	@MockitoBean RestTemplate restTemplate;
	@MockitoBean StringRedisTemplate redisTemplate;
	@Autowired DataSource dataSource;
	@Autowired ApplicationContext context;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
		}
		assertThat(context.getBeansOfType(R2DeletionScheduler.class)).isEmpty();
		verifyNoInteractions(s3Client, restTemplate, redisTemplate);
	}

}

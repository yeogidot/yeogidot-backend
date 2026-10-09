package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;

/** 명시적 옵트인과 신규 로컬 테스트 스키마가 있어야만 실행한다. R2는 가짜다. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_MYSQL_TEST", matches = "true")
@ContextConfiguration(classes = R2DeletionOperationsMySqlIntegrationTest.MySqlConfig.class, inheritLocations = false)
@TestPropertySource(properties = "r2.mysql.suffix=photo")
class R2DeletionOperationsMySqlIntegrationTest extends R2DeletionOperationsIntegrationTest {
    @Override protected void verifyDatabase(DataSource source) throws Exception {
        R2DeletionMySqlTestConfiguration.verify(source);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(R2DeletionOperationsService.class)
    static class MySqlConfig extends R2DeletionMySqlTestConfiguration {
        @Override @Bean DataSource dataSource(Environment environment) throws Exception {
            DataSource source = super.dataSource(environment);
            new ResourceDatabasePopulator(new ClassPathResource("db/manual/20261009_create_r2_deletion_retry_audit.sql"))
                    .execute(source);
            return source;
        }
    }
}

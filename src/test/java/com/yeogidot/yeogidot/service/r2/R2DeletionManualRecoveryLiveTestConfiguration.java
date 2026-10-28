package com.yeogidot.yeogidot.service.r2;

import com.yeogidot.yeogidot.service.R2DeletionOperationsService;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/** 별도 옵트인/빈 로컬 DB/테스트 버킷 검증 후 기존 도메인 DDL와 요청 이력 DDL를 준비한다. */
@TestConfiguration
@Import(R2DeletionOperationsService.class)
class R2DeletionManualRecoveryLiveTestConfiguration extends R2DeletionLiveTestConfiguration {
    @Override @Bean
    DataSource dataSource(Environment environment) throws Exception {
        R2DeletionManualRecoveryProcess.target(System.getenv()); // DB 연결 전에 운영 DB 이름 등을 거부한다.
        DataSource source = super.dataSource(environment);
        new ResourceDatabasePopulator(new ClassPathResource("db/manual/20261009_create_r2_deletion_retry_audit.sql"))
                .execute(source);
        return source;
    }
}

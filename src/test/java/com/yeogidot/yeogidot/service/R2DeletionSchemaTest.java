package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class R2DeletionSchemaTest {
    @Test void 수동_DDL을_H2_MySQL모드에서_실행해_작업을_저장한다() {
        // MySQL 실서버의 마이그레이션 검증을 대신하지는 않는다.
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        try {
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/manual/20260917_create_r2_deletion_task.sql")).execute(dataSource);
            String insert = "insert into r2_deletion_task "
                    + "(file_url, status, attempt_count, available_at, created_at, updated_at) "
                    + "values (?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
            jdbc.update(insert, "https://r2-test.invalid/test.jpg", "PENDING");
            assertThat(jdbc.queryForObject("select count(*) from r2_deletion_task", Integer.class))
                    .isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update(insert, "https://r2-test.invalid/test.jpg", "INVALID"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }
}

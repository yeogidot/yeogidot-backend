package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 기존 10개 서비스 검증을 실제 MySQL에서도 실행한다. 운영 설정/Redis/R2는 로드하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_NICKNAME_MYSQL_TEST", matches = "true")
@ContextConfiguration(classes = AuthNicknameMySqlIntegrationTest.MySqlConfig.class, inheritLocations = false)
class AuthNicknameMySqlIntegrationTest extends AuthNicknameIntegrationTest {
    @Autowired DataSource source;
    @Autowired MigrationEvidence migrationEvidence;

    @Test
    void migrationPreservesLegacyAccountWithUnsetNickname() {
        assertThat(migrationEvidence.preservedLegacyAccount()).isTrue();
    }

    @Test
    void deploymentSqlSetsBinaryCollationAndUniqueIndex() {
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForList("SELECT COLLATION_NAME FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='users' "
                + "AND column_name IN ('nickname','nickname_key')", String.class))
                .containsExactlyInAnyOrder("utf8mb4_bin", "utf8mb4_bin");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='users' "
                + "AND index_name='uk_users_nickname_key' AND non_unique=0 "
                + "AND column_name='nickname_key'", Integer.class)).isEqualTo(1);
    }

    @Test
    void databaseRejectsExactDuplicateEvenWithoutServicePrecheck() {
        var jdbc = new JdbcTemplate(source);
        insertDirectly(jdbc, "direct1@example.com", "DirectName");
        assertThatThrownBy(() -> insertDirectly(jdbc, "direct2@example.com", "DirectName"))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void distinctHangulNamesRemainDistinct() {
        auth.signup(signup("hangul1@example.com", "가나"), null);
        auth.signup(signup("hangul2@example.com", "까나"), null);
        assertThat(users.count()).isEqualTo(2);
    }

    private static void insertDirectly(JdbcTemplate jdbc, String email, String nickname) {
        jdbc.update("INSERT INTO users (email,password,password_changed_at,createdDate,modifiedDate,"
                        + "nickname,nickname_key) VALUES (?, 'test-only', NOW(6), NOW(6), NOW(6), ?, ?)",
                email, nickname, nickname);
    }

    record MigrationEvidence(boolean preservedLegacyAccount) {}

    static String jdbcUrl(String schema, String port) {
        if (schema == null || !schema.matches("yeogidot_nickname_test_[0-9]{8}_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("A fresh dedicated nickname test schema is required");
        }
        if (port == null || !port.matches("[0-9]{1,5}")
                || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
            throw new IllegalArgumentException("Invalid local MySQL port");
        }
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema
                + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true";
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import(AuthService.class)
    static class MySqlConfig extends AuthNicknameIntegrationTest.Config {
        private MigrationEvidence migrationEvidence;

        @Override
        @Bean
        DataSource dataSource() {
            if (!"true".equals(System.getenv("RUN_NICKNAME_MYSQL_TEST"))) {
                throw new IllegalStateException("Explicit MySQL test opt-in required");
            }
            String schema = required("NICKNAME_TEST_DB_NAME");
            var dataSource = new DriverManagerDataSource(
                    jdbcUrl(schema, required("NICKNAME_TEST_DB_PORT")),
                    required("NICKNAME_TEST_DB_USERNAME"), required("NICKNAME_TEST_DB_PASSWORD"));
            var jdbc = new JdbcTemplate(dataSource);
            if (!schema.equals(jdbc.queryForObject("SELECT DATABASE()", String.class))) {
                throw new IllegalStateException("Unexpected database target");
            }
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE()", Integer.class);
            if (count == null || count != 0) {
                throw new IllegalStateException("Refusing to initialize a non-empty test database");
            }

            // Only this verified, empty, newly created schema is initialized.
            var bootstrap = super.entityManagerFactory(dataSource);
            bootstrap.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only",
                    "hibernate.hbm2ddl.halt_on_error", "true"));
            bootstrap.afterPropertiesSet();
            bootstrap.destroy();
            // Reconstruct the pre-nickname users table, then apply the exact deployment SQL.
            jdbc.execute("ALTER TABLE users DROP INDEX uk_users_nickname_key, "
                    + "DROP COLUMN nickname, DROP COLUMN nickname_key");
            jdbc.update("INSERT INTO users (email,password,password_changed_at,createdDate,modifiedDate) "
                    + "VALUES ('legacy-migration@example.com','test-only',NOW(6),NOW(6),NOW(6))");
            new ResourceDatabasePopulator(new ClassPathResource(
                    "db/manual/20261001_add_user_nickname.sql")).execute(dataSource);
            Integer preserved = jdbc.queryForObject("SELECT COUNT(*) FROM users "
                    + "WHERE email='legacy-migration@example.com' AND password='test-only' "
                    + "AND nickname IS NULL AND nickname_key IS NULL", Integer.class);
            if (preserved == null || preserved != 1) {
                throw new IllegalStateException("Nickname migration did not preserve the legacy account");
            }
            migrationEvidence = new MigrationEvidence(true);
            System.out.println("Nickname MySQL test: host=127.0.0.1, schema=" + schema
                    + ", version=" + jdbc.queryForObject("SELECT VERSION()", String.class)
                    + ", nickname DDL=manual, legacy account preserved=true");
            return dataSource;
        }

        @Override
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = super.entityManagerFactory(dataSource);
            // Never let Hibernate update/repair the deployment SQL being tested.
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        MigrationEvidence migrationEvidence(DataSource dataSource) { return migrationEvidence; }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
            return value;
        }
    }
}

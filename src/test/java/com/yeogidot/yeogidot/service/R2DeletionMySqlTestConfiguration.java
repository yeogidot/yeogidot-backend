package com.yeogidot.yeogidot.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import javax.sql.DataSource;
import java.util.Map;

/** Opt-in only. Refuses non-loopback, non-test schemas and pre-existing tables. */
@Configuration(proxyBeanMethods = false)
class R2DeletionMySqlTestConfiguration extends R2DeletionTestConfiguration {
    @Bean
    DataSource dataSource(Environment environment) throws Exception {
        if (!"true".equals(System.getenv("RUN_R2_MYSQL_TEST"))) {
            throw new IllegalStateException("Explicit MySQL test opt-in required");
        }
        String base = required("R2_TEST_DB_NAME");
        if (!base.matches("yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}")) {
            throw new IllegalArgumentException("A fresh, dedicated R2 test schema is required");
        }
        String suffix = environment.getRequiredProperty("r2.mysql.suffix");
        if (!suffix.equals("photo") && !suffix.equals("batch")) {
            throw new IllegalArgumentException("Unknown test schema suffix");
        }
        String port = required("R2_TEST_DB_PORT");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid port");
        var source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" + port + "/" + base + "_" + suffix
                        + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                required("R2_TEST_DB_USERNAME"), required("R2_TEST_DB_PASSWORD"));
        verify(source);
        var jdbc = new JdbcTemplate(source);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class);
        if (count == null || count != 0) {
            throw new IllegalStateException("Refusing to initialize a non-empty test database");
        }
        // Only in the checked EMPTY test schema: create the surrounding domain tables.
        var bootstrap = super.entityManagerFactory(source);
        bootstrap.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only",
                "hibernate.hbm2ddl.halt_on_error", "true"));
        bootstrap.afterPropertiesSet();
        bootstrap.destroy();
        // Replace only the freshly generated task table with the exact deployment DDL.
        jdbc.execute("DROP TABLE r2_deletion_task");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/manual/20260917_create_r2_deletion_task.sql")).execute(source);
        System.out.println("R2 MySQL test: version=" + jdbc.queryForObject("SELECT VERSION()", String.class)
                + ", isolation=" + jdbc.queryForObject("SELECT @@transaction_isolation", String.class)
                + ", schema=" + base + "_" + suffix + ", storage=FAKE, task DDL=manual");
        return source;
    }

    @Override
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
        var factory = super.entityManagerFactory(source);
        // Do not let Hibernate repair the manual DDL being verified.
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        return factory;
    }

    static void verify(DataSource source) throws Exception {
        String base = required("R2_TEST_DB_NAME");
        if (!base.matches("yeogidot_r2_test_[0-9]{8}_[a-f0-9]{8}")) {
            throw new IllegalStateException("Invalid test schema");
        }
        try (var connection = source.getConnection()) {
            String catalog = connection.getCatalog();
            if (!connection.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:")
                    || !(catalog.equals(base + "_photo") || catalog.equals(base + "_batch"))) {
                throw new IllegalStateException("Unexpected database target");
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}

package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;

/** Runs identical batch-boundary and partial-failure assertions on MySQL. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_MYSQL_TEST", matches = "true")
@ContextConfiguration(classes = R2DeletionMySqlTestConfiguration.class, inheritLocations = false)
@TestPropertySource(properties = "r2.mysql.suffix=batch")
class R2DeletionBatchMySqlIntegrationTest extends R2DeletionBatchIntegrationTest {
    @Override
    protected void verifyTestDatabase(DataSource source) throws Exception {
        R2DeletionMySqlTestConfiguration.verify(source);
    }
}

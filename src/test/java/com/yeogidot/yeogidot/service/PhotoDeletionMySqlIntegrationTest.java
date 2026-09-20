package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import javax.sql.DataSource;

/** Runs the same 14 recovery assertions against isolated MySQL, with fake R2. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_MYSQL_TEST", matches = "true")
@ContextConfiguration(classes = R2DeletionMySqlTestConfiguration.class, inheritLocations = false)
@TestPropertySource(properties = "r2.mysql.suffix=photo")
class PhotoDeletionMySqlIntegrationTest extends PhotoDeletionFailureIntegrationTest {
    @Override
    protected void verifyTestDatabase(DataSource source) throws Exception {
        R2DeletionMySqlTestConfiguration.verify(source);
    }
}

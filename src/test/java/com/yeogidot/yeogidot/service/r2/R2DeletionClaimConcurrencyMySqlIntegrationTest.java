package com.yeogidot.yeogidot.service.r2;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

/** Same real-claim race assertions on a fresh, loopback-only MySQL schema and deployment task DDL. */
@EnabledIfEnvironmentVariable(named = "RUN_R2_MYSQL_TEST", matches = "true")
@ContextConfiguration(classes = R2DeletionMySqlTestConfiguration.class, inheritLocations = false)
@TestPropertySource(properties = "r2.mysql.suffix=claim")
class R2DeletionClaimConcurrencyMySqlIntegrationTest extends R2DeletionClaimConcurrencyIntegrationTest {
    @Override
    protected void verifyTestDatabase(DataSource source) throws Exception {
        R2DeletionMySqlTestConfiguration.verify(source);
    }
}

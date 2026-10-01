package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class NicknameMySqlSafetyTest {
    @Test
    void jdbcTargetIsAlwaysLoopbackAndDedicatedTestSchema() {
        assertThat(AuthNicknameMySqlIntegrationTest.jdbcUrl("yeogidot_nickname_test_20261001_ab123456", "3306"))
                .startsWith("jdbc:mysql://127.0.0.1:3306/yeogidot_nickname_test_20261001_ab123456?");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yeogidot", "mysql", "yeogidot_nickname_test", "yeogidot_nickname_test_20261001_ab123456?x=y"})
    void refusesOtherSchemas(String schema) {
        assertThatThrownBy(() -> AuthNicknameMySqlIntegrationTest.jdbcUrl(schema, "3306"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "65536", "-1", "3306/yeogidot", "3306?x=y"})
    void refusesInvalidPorts(String port) {
        assertThatThrownBy(() -> AuthNicknameMySqlIntegrationTest.jdbcUrl("yeogidot_nickname_test_20261001_ab123456", port))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

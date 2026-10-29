package com.yeogidot.yeogidot.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeploymentHealthControllerTest {
    @Test
    void reportsUnavailableUntilReadyAndDuringShutdown() {
        var availability = mock(ApplicationAvailability.class);
        var controller = new DeploymentHealthController(availability, "release-123");
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.ready().getBody()).isEqualTo("NOT_READY");
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(controller.ready().getBody()).isEqualTo("release-123");
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
    }
}

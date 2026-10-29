package com.yeogidot.yeogidot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP 준비 상태만 확인한다. DB·Redis·외부 API 상태를 보장하지 않는다. */
@RestController
public class DeploymentHealthController {
    private final ApplicationAvailability availability;
    private final String release;

    public DeploymentHealthController(ApplicationAvailability availability,
            @Value("${deployment.release:development}") String release) {
        this.availability = availability;
        this.release = release;
    }

    @GetMapping(value = "/health/ready", produces = "text/plain")
    public ResponseEntity<String> ready() {
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        return ResponseEntity.status(ready ? 200 : 503).cacheControl(CacheControl.noStore())
                .body(ready ? release : "NOT_READY");
    }
}

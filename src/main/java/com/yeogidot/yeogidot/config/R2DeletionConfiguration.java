package com.yeogidot.yeogidot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(R2DeletionSettings.class)
public class R2DeletionConfiguration {
    @Bean("r2DeletionClock")
    public Clock r2DeletionClock() {
        return Clock.systemUTC();
    }
}

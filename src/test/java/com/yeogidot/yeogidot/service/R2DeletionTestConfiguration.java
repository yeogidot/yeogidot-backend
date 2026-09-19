package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.config.R2DeletionSettings;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

/** 애플리케이션 설정/외부 클라이언트/스케줄러 없이 필요한 빈만 생성한다. */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = PhotoRepository.class)
@Import({PhotoService.class, OwnershipValidator.class, R2DeletionTaskService.class, R2DeletionWorker.class})
class R2DeletionTestConfiguration {
    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(Photo.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "update"));
        return factory;
    }

    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
    @Bean("r2DeletionClock") MutableClock clock() { return new MutableClock(); }
    @Bean R2DeletionSettings settings() { return new R2DeletionSettings(3, 1, 4, 60, 20); }
    @Bean GcsService gcsService() { return mock(GcsService.class); }
    @Bean GeoCodingService geoCodingService() { return mock(GeoCodingService.class); }
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }

    static final class MutableClock extends Clock {
        static final Instant START = Instant.parse("2026-09-17T00:00:00Z");
        private final AtomicReference<Instant> now = new AtomicReference<>(START);
        void reset() { now.set(START); }
        void advance(long seconds) { now.updateAndGet(i -> i.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}

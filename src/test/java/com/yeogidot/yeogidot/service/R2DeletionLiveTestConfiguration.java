package com.yeogidot.yeogidot.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.services.s3.S3Client;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration
class R2DeletionLiveTestConfiguration extends R2DeletionMySqlTestConfiguration {
    @Override @Bean
    DataSource dataSource(Environment environment) throws Exception {
        R2LiveTestStorage.validate(System.getenv()); // Before any DB DDL, validate the live target too.
        return super.dataSource(environment);
    }

    @Bean(destroyMethod = "close")
    R2LiveTestStorage liveStorage() { return new R2LiveTestStorage(System.getenv()); }

    @Override @Bean
    FaultInjectingGcsService gcsService() {
        return new FaultInjectingGcsService(liveStorage().client());
    }

    @Override protected String storageDescription() { return "LIVE_TEST_BUCKET"; }

    static final class FaultInjectingGcsService extends GcsService {
        final AtomicBoolean failNext = new AtomicBoolean();
        FaultInjectingGcsService(S3Client client) { super(client); }
        @Override public void deleteFileStrict(String url) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalStateException("Injected before HTTP; not a real R2 outage");
            }
            super.deleteFileStrict(url);
        }
    }
}

package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Validates live bean wiring with H2/mock S3 only; never opens a network client. */
@SpringJUnitConfig(R2DeletionLiveWiringTest.Config.class)
@TestPropertySource(properties = {"r2.bucket=yeogidot-r2-delete-test", "r2.public-url=https://r2-test.invalid"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class R2DeletionLiveWiringTest {
    @Autowired R2LiveTestStorage storage;
    @Autowired R2DeletionLiveTestConfiguration.FaultInjectingGcsService service;

    @Test void 실제_서비스_설정과_실패주입을_외부접속없이_확인한다() {
        service.failNext.set(true);
        assertThatThrownBy(() -> service.deleteFileStrict("https://r2-test.invalid/fixture.txt"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storage.client());
        service.deleteFileStrict("https://r2-test.invalid/fixture.txt");
        verify(storage.client()).deleteObject(argThat((DeleteObjectRequest request) ->
                request.bucket().equals(R2LiveTestStorage.BUCKET) && request.key().equals("fixture.txt")));
    }

    @Configuration
    static class Config extends R2DeletionLiveTestConfiguration {
        @Override @Bean(destroyMethod = "shutdown") DataSource dataSource(Environment environment) {
            return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        }
        @Override @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            return new R2DeletionTestConfiguration().entityManagerFactory(source);
        }
        @Override @Bean R2LiveTestStorage liveStorage() {
            var storage = mock(R2LiveTestStorage.class);
            when(storage.client()).thenReturn(mock(S3Client.class));
            return storage;
        }
    }
}

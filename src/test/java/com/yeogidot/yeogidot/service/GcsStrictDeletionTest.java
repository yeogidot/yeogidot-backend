package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GcsStrictDeletionTest {
    private S3Client client;
    private GcsService storage;

    @BeforeEach void setUp() {
        client = mock(S3Client.class);
        storage = new GcsService(client);
        ReflectionTestUtils.setField(storage, "publicUrl", "https://cdn.example.invalid");
        ReflectionTestUtils.setField(storage, "bucketName", "test-only");
    }

    @Test void 올바른_버킷과_키로_시간제한을_두고_삭제한다() {
        storage.deleteFileStrict("https://cdn.example.invalid/test.jpg");
        var capture = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(capture.capture());
        var request = capture.getValue();
        assertThat(request.bucket()).isEqualTo("test-only");
        assertThat(request.key()).isEqualTo("test.jpg");
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout())
                .contains(Duration.ofSeconds(30));
        assertThat(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout())
                .contains(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "https://other.invalid/test.jpg", "https://cdn.example.invalid.evil/test.jpg",
            "https://cdn.example.invalid", "https://cdn.example.invalid/"})
    void 잘못된_주소는_외부호출_없이_거절한다(String url) {
        assertThatThrownBy(() -> storage.deleteFileStrict(url)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test void 이미_없는_파일은_완료로_처리할_수_있다() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(error(404, "NoSuchKey"));
        assertThatCode(() -> storage.deleteFileStrict("https://cdn.example.invalid/test.jpg"))
                .doesNotThrowAnyException();
    }

    @Test void 설정_URL의_끝에_슬래시가_있어도_업로드와_같은_키를_사용한다() {
        ReflectionTestUtils.setField(storage, "publicUrl", "https://cdn.example.invalid/");
        storage.deleteFileStrict("https://cdn.example.invalid//test.jpg");
        var capture = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(capture.capture());
        assertThat(capture.getValue().key()).isEqualTo("test.jpg");
    }

    @Test void 없는_버킷을_없는_파일과_혼동하지_않는다() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(error(404, "NoSuchBucket"));
        assertThatThrownBy(() -> storage.deleteFileStrict("https://cdn.example.invalid/test.jpg"))
                .isInstanceOf(S3Exception.class);
    }

    private S3Exception error(int status, String code) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (S3Exception) builder.build();
    }
}

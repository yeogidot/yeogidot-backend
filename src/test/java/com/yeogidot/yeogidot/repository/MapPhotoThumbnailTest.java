package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.service.GcsService;
import com.yeogidot.yeogidot.service.MapPhotoThumbnailWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MapPhotoThumbnailTest {
    private static final String BASE_URL = "https://storage.example.test";
    private static final String SOURCE = BASE_URL + "/photo.jpg";

    @Autowired PhotoRepository photos;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void savesThumbnailForAnExistingPhotoAndDoesNotRunItTwice() throws IOException {
        Photo photo = existingPhoto();
        GcsService storage = mock(GcsService.class);
        byte[] thumbnail = {1, 2, 3};
        when(storage.createMapThumbnail(SOURCE)).thenReturn(thumbnail);
        var worker = new MapPhotoThumbnailWorker(photos, storage, transactions);

        worker.runOnce();
        worker.runOnce();

        Photo updated = photos.findById(photo.getId()).orElseThrow();
        assertThat(updated.getThumbnailPath()).isEqualTo(photo.mapThumbnailObjectUrl());
        assertThat(updated.getFilePath()).isEqualTo(SOURCE);
        assertThat(updated.storageFileUrls()).containsExactly(SOURCE, photo.mapThumbnailObjectUrl());
        verify(storage).createMapThumbnail(SOURCE);
        verify(storage).uploadMapThumbnail(photo.mapThumbnailObjectUrl(), thumbnail);
    }

    @Test
    void failedObjectUploadKeepsTheThumbnailPendingAndBacksOff() throws IOException {
        Photo photo = existingPhoto();
        GcsService storage = mock(GcsService.class);
        byte[] thumbnail = {1, 2, 3};
        when(storage.createMapThumbnail(SOURCE)).thenReturn(thumbnail);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(storage).uploadMapThumbnail(photo.mapThumbnailObjectUrl(), thumbnail);
        var worker = new MapPhotoThumbnailWorker(photos, storage, transactions);

        worker.runOnce();
        worker.runOnce();

        Photo pending = photos.findById(photo.getId()).orElseThrow();
        assertThat(pending.getThumbnailPath()).isNull();
        assertThat(pending.getThumbnailAttempts()).isEqualTo(1);
        assertThat(pending.getThumbnailRetryAt()).isNotNull();
        verify(storage).createMapThumbnail(SOURCE);
    }

    @Test
    void skipsPublishingWhenThePhotoWasDeletedDuringGeneration() throws IOException {
        Photo photo = existingPhoto();
        GcsService storage = mock(GcsService.class);
        when(storage.createMapThumbnail(SOURCE)).thenAnswer(invocation -> {
            photos.deleteById(photo.getId());
            return new byte[]{1, 2, 3};
        });
        var worker = new MapPhotoThumbnailWorker(photos, storage, transactions);

        worker.runOnce();

        assertThat(photos.existsById(photo.getId())).isFalse();
        verify(storage, never()).uploadMapThumbnail(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"1600,800,192,96", "400,800,96,192", "96,48,96,48", "2000,1,192,1"})
    void preservesAspectRatioAndNeverUpscales(int width, int height, int expectedWidth, int expectedHeight)
            throws IOException {
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(source, "jpeg", bytes)).isTrue();
        assertThumbnailSize(bytes.toByteArray(), expectedWidth, expectedHeight);
    }

    @Test
    void readsTheWebpFormatAcceptedByUploads() throws IOException {
        byte[] webp = Base64.getDecoder().decode(
                "UklGRqoAAABXRUJQVlA4IJ4AAADwDQCdASpAAaAAPm02mUmkIyKhICgAgA2JaW7hdrEWgAnsA99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtk5D32ych77ZOQ99snIe+2TkPfbJyHvtjgAA/v7cN/8GyRTcj//8gx/oa/wV3oQeM1aYJtu/8BAAAAAAAAAAAA==");
        assertThumbnailSize(webp, 192, 96);
    }

    @Test
    void deletesTheDeterministicThumbnailKeyForTheOriginal() {
        S3Client s3 = mock(S3Client.class);
        var storage = new GcsService(s3);
        ReflectionTestUtils.setField(storage, "bucketName", "test-only");
        ReflectionTestUtils.setField(storage, "publicUrl", BASE_URL);

        storage.deleteMapThumbnailFor(SOURCE);

        verify(s3).deleteObject(argThat((DeleteObjectRequest request) -> request.key().equals("photo.jpg.map-s.jpg")));
    }

    private Photo existingPhoto() {
        User owner = users.saveAndFlush(User.builder()
                .email(UUID.randomUUID() + "@example.test").password("test").build());
        return photos.saveAndFlush(Photo.builder().user(owner).filePath(SOURCE)
                .latitude(BigDecimal.valueOf(35.2)).longitude(BigDecimal.valueOf(129.1))
                .takenAt(LocalDateTime.of(2026, 11, 20, 10, 0)).build());
    }

    private void assertThumbnailSize(byte[] input, int expectedWidth, int expectedHeight) throws IOException {
        S3Client s3 = mock(S3Client.class);
        var storage = new GcsService(s3);
        ReflectionTestUtils.setField(storage, "bucketName", "test-only");
        ReflectionTestUtils.setField(storage, "publicUrl", BASE_URL);
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().build(), new ByteArrayInputStream(input)));

        byte[] thumbnail = storage.createMapThumbnail(SOURCE);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(image.getWidth()).isEqualTo(expectedWidth);
        assertThat(image.getHeight()).isEqualTo(expectedHeight);
    }
}

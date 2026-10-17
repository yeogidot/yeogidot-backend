package com.yeogidot.yeogidot.service.photo.upload;

import com.yeogidot.yeogidot.service.GcsService;
import com.yeogidot.yeogidot.service.GeoCodingService;
import com.yeogidot.yeogidot.service.PhotoService;
import com.yeogidot.yeogidot.service.R2DeletionTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PhotoUploadValidationTest {
    private final PhotoRepository photos = mock(PhotoRepository.class);
    private final GcsService storage = mock(GcsService.class);
    private final User owner = User.builder().id(1L).email("test@example.invalid").password("unused").build();
    private PhotoService service;

    @BeforeEach void setup() {
        service = new PhotoService(photos, mock(CommentRepository.class), storage, mock(GeoCodingService.class),
                new ObjectMapper(), mock(TravelDayRepository.class), new OwnershipValidator(), mock(R2DeletionTaskService.class));
    }

    @Test void 허용하지_않은_확장자는_업로드하지_않는다() {
        assertThatThrownBy(() -> service.uploadPhotos(List.of(new MockMultipartFile("files", "a.exe", "image/jpeg", new byte[]{1})), "[]", owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("확장자");
        verifyNoInteractions(storage, photos);
    }

    @Test void 허용하지_않은_MIME은_업로드하지_않는다() {
        assertThatThrownBy(() -> service.uploadPhotos(List.of(new MockMultipartFile("files", "a.jpg", "text/plain", new byte[]{1})), "[]", owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("파일 형식");
        verifyNoInteractions(storage, photos);
    }

    @Test void 확장자와_MIME이_정상이어도_이미지_내용이_아니면_거절한다() {
        assertThatThrownBy(() -> service.uploadPhotos(List.of(new MockMultipartFile("files", "a.jpg", "image/jpeg", new byte[]{1, 2, 3})), "[]", owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이미지");
        verifyNoInteractions(storage, photos);
    }

    @Test void 두번째_파일이_잘못되어도_첫번째_파일부터_전송하지_않는다() throws Exception {
        assertThatThrownBy(() -> service.uploadPhotos(List.of(png(), new MockMultipartFile("files", "bad.jpg", "image/jpeg", new byte[]{1})), "[]", owner))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, photos);
    }

    @Test void 검증_읽기실패_후에도_다음_사진을_처리한다() throws Exception {
        var broken = new MockMultipartFile("files", "broken.png", "image/png", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("test-read-failure"); }
        };
        assertThatThrownBy(() -> service.uploadPhotos(List.of(broken), "[]", owner))
                .isInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("test-read-failure");
        var good = png();
        when(storage.uploadFile(good)).thenReturn("https://storage.example.invalid/good.png");
        when(photos.save(any(Photo.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service.uploadPhotos(List.of(good), "[{\"originalName\":\"good.png\",\"takenAt\":\"2026-10-17T10:00:00\"}]", owner);
        verify(storage).uploadFile(good);
    }

    @Test void 잘못된_메타데이터에는_사진을_전송하지_않는다() throws Exception {
        var good = png();
        assertThatThrownBy(() -> service.uploadPhotos(List.of(good), "not-json", owner))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("메타데이터");
        verifyNoInteractions(storage, photos);
    }

    @Test void 검증_대기가_인터럽트되면_플래그를_유지하고_업로드하지_않는다() throws Exception {
        var good = png();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.uploadPhotos(List.of(good), "[]", owner))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("인터럽트");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(storage, photos);
        } finally { Thread.interrupted(); }
    }

    private MockMultipartFile png() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return new MockMultipartFile("files", "good.png", "image/png", out.toByteArray());
        } finally { image.flush(); }
    }
}

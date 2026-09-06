package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 병렬 사진 업로드 실패 시 R2 보상 삭제 회귀 테스트.
 *
 * <p>운영 코드 수정 전에는 일부 테스트가 실패하는 것이 정상이다.</p>
 */
@ExtendWith(MockitoExtension.class)
class PhotoUploadCompensationTest {

    @Mock
    private PhotoRepository photoRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private GcsService gcsService;
    @Mock
    private GeoCodingService geoCodingService;
    @Mock
    private TravelDayRepository travelDayRepository;

    private PhotoService photoService;
    private User owner;

    @BeforeEach
    void setUp() {
        photoService = new PhotoService(
                photoRepository,
                commentRepository,
                gcsService,
                geoCodingService,
                new ObjectMapper(),
                travelDayRepository,
                new OwnershipValidator()
        );

        owner = User.builder()
                .id(1L)
                .email("owner@example.com")
                .password("encoded-password")
                .build();
    }

    @Test
    void R2_업로드후_후처리가_실패해도_해당_파일을_보상_삭제한다() throws Exception {
        String uploadedUrl = "https://cdn.example.com/post-process-failure.png";
        MultipartFile file = png("post-process-failure.png");
        String metadata = """
                [{
                  "originalName": "post-process-failure.png",
                  "takenAt": "2026-07-30T10:00:00",
                  "latitude": 37.5665,
                  "longitude": 126.9780
                }]
                """;

        when(gcsService.uploadFile(file)).thenReturn(uploadedUrl);
        when(geoCodingService.getDistrictFromCoordinates(any(BigDecimal.class), any(BigDecimal.class)))
                .thenThrow(new IllegalStateException("Kakao API failure"));

        assertThrows(
                RuntimeException.class,
                () -> photoService.uploadPhotos(List.of(file), metadata, owner)
        );

        verify(gcsService).deleteFile(uploadedUrl);
        verify(photoRepository, never()).save(any());
    }

    @Test
    void 한_Future가_실패해도_뒤늦게_완료된_모든_R2_업로드를_보상_삭제한다() throws Exception {
        Assumptions.assumeTrue(
                ForkJoinPool.getCommonPoolParallelism() >= 2,
                "병렬 Future 순서 검증에는 common pool parallelism 2 이상이 필요합니다."
        );

        MultipartFile failingFile = png("a-failing.png");
        MultipartFile successfulFileB = png("b-success.png");
        MultipartFile successfulFileC = png("c-success.png");
        String uploadedUrlB = "https://cdn.example.com/b-success.png";
        String uploadedUrlC = "https://cdn.example.com/c-success.png";
        CountDownLatch successfulUploadsFinished = new CountDownLatch(2);

        when(gcsService.uploadFile(any(MultipartFile.class))).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(0);
            return switch (file.getOriginalFilename()) {
                case "a-failing.png" -> {
                    if (!successfulUploadsFinished.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting for successful uploads");
                    }
                    throw new IOException("Forced upload failure");
                }
                case "b-success.png" -> {
                    successfulUploadsFinished.countDown();
                    yield uploadedUrlB;
                }
                case "c-success.png" -> {
                    successfulUploadsFinished.countDown();
                    yield uploadedUrlC;
                }
                default -> throw new IllegalArgumentException("Unexpected file: " + file.getOriginalFilename());
            };
        });

        String metadata = """
                [
                  {"originalName":"a-failing.png","takenAt":"2026-07-30T10:00:00"},
                  {"originalName":"b-success.png","takenAt":"2026-07-30T10:01:00"},
                  {"originalName":"c-success.png","takenAt":"2026-07-30T10:02:00"}
                ]
                """;

        assertTimeout(Duration.ofSeconds(10), () ->
                assertThrows(RuntimeException.class, () ->
                        photoService.uploadPhotos(
                                List.of(failingFile, successfulFileB, successfulFileC),
                                metadata,
                                owner
                        )
                )
        );

        verify(gcsService).deleteFile(uploadedUrlB);
        verify(gcsService).deleteFile(uploadedUrlC);
        verify(photoRepository, never()).save(any());
    }

    private MultipartFile png(String filename) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new MockMultipartFile("files", filename, "image/png", output.toByteArray());
    }
}

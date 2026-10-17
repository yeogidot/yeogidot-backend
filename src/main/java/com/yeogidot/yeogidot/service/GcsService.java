package com.yeogidot.yeogidot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.builders.ThumbnailParameterBuilder;
import net.coobird.thumbnailator.tasks.io.InputStreamImageSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Cloudflare R2 Storage 파일 업로드 서비스 (S3 호환 API)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GcsService {

    @Value("${r2.bucket}")
    private String bucketName;

    @Value("${r2.public-url}")
    private String publicUrl;

    private final S3Client s3Client;

    /**
     * R2에 파일 업로드 후 퍼블릭 URL 반환
     */
    public String uploadFile(MultipartFile file) throws IOException {
        String uuid = UUID.randomUUID().toString();
        String originalFilename = file.getOriginalFilename();
        String extension = "";

        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }

        String fileName = uuid + extension;

        log.info("📤 R2 업로드 시작: {} → {}", originalFilename, fileName);

        byte[] uploadBytes = compressIfImage(file);
        log.info("📦 파일 크기: {}KB → {}KB", file.getSize() / 1024, uploadBytes.length / 1024);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(putRequest, RequestBody.fromBytes(uploadBytes));

        String fileUrl = publicUrl + "/" + fileName;
        log.info("✅ R2 업로드 완료: {}", fileUrl);

        return fileUrl;
    }

    /**
     * 이미지 압축 (JPEG/PNG → 품질 90%로 압축)
     */
    private byte[] compressIfImage(MultipartFile file) throws IOException {
        String contentType = file.getContentType();

        if (contentType == null ||
                (!contentType.equals("image/jpeg") && !contentType.equals("image/png"))) {
            return file.getBytes();
        }

        // 원본 크기의 일반 RGB 이미지에는 크기 변환용 복사와 JPEG RGB 복사가 필요 없다.
        // EXIF 읽기·회전은 기존 라이브러리로 처리하고, JPEG 품질은 동일하게 유지한다.
        try (InputStream input = file.getInputStream()) {
            BufferedImage image = readOrientedImage(input);
            BufferedImage jpeg = image;
            try {
                if (!isJpegRgb(image)) {
                    // 투명·회색조·팔레트 이미지에는 기존과 같은 RGB/검은 배경 변환을 적용한다.
                    jpeg = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = jpeg.createGraphics();
                    try { graphics.drawImage(image, 0, 0, null); }
                    finally { graphics.dispose(); }
                }
                var writers = ImageIO.getImageWritersByFormatName("JPEG");
                if (!writers.hasNext()) throw new IOException("JPEG 인코더를 사용할 수 없습니다.");
                ImageWriter writer = writers.next();
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    try (ImageOutputStream encoded = ImageIO.createImageOutputStream(output)) {
                        if (encoded == null) throw new IOException("JPEG 출력 스트림을 만들 수 없습니다.");
                        writer.setOutput(encoded);
                        ImageWriteParam parameters = writer.getDefaultWriteParam();
                        parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        parameters.setCompressionQuality(0.9f);
                        writer.write(null, new IIOImage(jpeg, null, null), parameters);
                        encoded.flush();
                    }
                    return output.toByteArray();
                } finally { writer.dispose(); }
            } finally {
                if (jpeg != image) jpeg.flush();
                image.flush();
            }
        }
    }

    private BufferedImage readOrientedImage(InputStream input) throws IOException {
        // 같은 ImageSource와 EXIF 필터를 사용하되, 일반 RGB에서 scale(1.0)의 복사는 생략한다.
        var parameters = new ThumbnailParameterBuilder().scale(1.0).useExifOrientation(true)
                .filters(new ArrayList<>()).build();
        var source = new InputStreamImageSource(input);
        source.setThumbnailParameter(parameters);
        BufferedImage image = source.read();
        try {
            if (!isJpegRgb(image)) {
                // 투명·팔레트·회색조 등은 기존 이미지 타입 변환 경로를 유지한다.
                BufferedImage converted = Thumbnails.of(image).scale(1.0).asBufferedImage();
                if (converted != image) image.flush();
                image = converted;
            }
            for (var filter : parameters.getImageFilters()) {
                BufferedImage oriented = filter.apply(image);
                if (oriented != image) image.flush();
                image = oriented;
            }
            return image;
        } catch (RuntimeException | Error failure) {
            image.flush();
            throw failure;
        }
    }

    private boolean isJpegRgb(BufferedImage image) {
        return image.getType() == BufferedImage.TYPE_3BYTE_BGR || image.getType() == BufferedImage.TYPE_INT_RGB;
    }

    /**
     * R2에서 파일 삭제
     */
    public void deleteFile(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(publicUrl)) {
            log.warn("R2 파일 URL 형식이 올바르지 않습니다: {}", fileUrl);
            return;
        }

        String objectName = fileUrl.substring(publicUrl.length() + 1);

        log.info("R2 파일 삭제 시도: {}", objectName);

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectName)
                .build();

        s3Client.deleteObject(deleteRequest);
        log.info("R2 파일 삭제 완료: {}", objectName);
    }

    /** 작업자는 잘못된 URL을 성공으로 간주하면 안 된다. 기존 삭제 경로와 구분한다. */
    public void deleteFileStrict(String fileUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new IllegalStateException("R2 public URL 설정이 없습니다.");
        }
        // uploadFile()이 만든 URL과 같은 접두사를 사용해야 실제 저장된 키를 얻는다.
        String prefix = publicUrl + "/";
        if (fileUrl == null || !fileUrl.startsWith(prefix) || fileUrl.length() == prefix.length()) {
            throw new IllegalArgumentException("설정된 R2 저장소의 파일 URL이 아닙니다.");
        }
        String key = fileUrl.substring(prefix.length());
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName).key(key)
                .overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
                .build();
        try {
            s3Client.deleteObject(request);
        } catch (S3Exception exception) {
            // 이미 없는 개체만 성공으로 취급한다. NoSuchBucket 등 다른 404는 삼키지 않는다.
            if (exception.statusCode() != 404 || exception.awsErrorDetails() == null
                    || !"NoSuchKey".equals(exception.awsErrorDetails().errorCode())) {
                throw exception;
            }
        }
    }
}

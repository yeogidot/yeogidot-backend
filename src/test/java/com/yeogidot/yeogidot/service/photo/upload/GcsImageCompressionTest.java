package com.yeogidot.yeogidot.service.photo.upload;

import com.yeogidot.yeogidot.service.GcsService;
import net.coobird.thumbnailator.Thumbnails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 실제 인코더를 실행하되 S3 요청만 메모리에서 확인한다. */
class GcsImageCompressionTest {
    private S3Client client;
    private GcsService storage;

    @BeforeEach void setup() {
        client = mock(S3Client.class);
        storage = new GcsService(client);
        ReflectionTestUtils.setField(storage, "bucketName", "test-only");
        ReflectionTestUtils.setField(storage, "publicUrl", "https://storage.example.invalid");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpeg", "png-rgb", "png-alpha", "png-gray", "png-indexed"})
    void 해상도와_출력픽셀을_기존_압축과_비교한다(String kind) throws Exception {
        String format = kind.equals("jpeg") ? "jpeg" : "png";
        int type = switch (kind) {
            case "png-alpha" -> BufferedImage.TYPE_INT_ARGB;
            case "png-gray" -> BufferedImage.TYPE_BYTE_GRAY;
            case "png-indexed" -> BufferedImage.TYPE_BYTE_INDEXED;
            default -> BufferedImage.TYPE_INT_RGB;
        };
        BufferedImage source = image(type);
        byte[] input = encode(source, format);
        byte[] actual = upload(input, "image/" + format, "photo." + format);
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(legacy(input)));
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(actual));
        assertThat(decoded.getWidth()).isEqualTo(expected.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(expected.getHeight()).isEqualTo(24);
        assertThat(pixels(decoded)).isEqualTo(pixels(expected));
        assertThat(actual[0] & 255).isEqualTo(255);
        assertThat(actual[1] & 255).isEqualTo(216);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void JPEG_EXIF_회전과_반전을_기존과_동일하게_유지한다(int orientation) throws Exception {
        byte[] input = withOrientation(encode(image(BufferedImage.TYPE_INT_RGB), "jpeg"), orientation);
        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(upload(input, "image/jpeg", "rotated.jpg")));
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(legacy(input)));
        assertThat(actual.getWidth()).isEqualTo(orientation >= 5 ? 24 : 40);
        assertThat(actual.getHeight()).isEqualTo(orientation >= 5 ? 40 : 24);
        assertThat(pixels(actual)).isEqualTo(pixels(expected));
    }

    @Test void 손상된_이미지는_전송하지_않는다() {
        assertThatThrownBy(() -> storage.uploadFile(new MockMultipartFile("files", "bad.jpg", "image/jpeg", new byte[]{1, 2, 3})))
                .isInstanceOf(IOException.class);
        verifyNoInteractions(client);
    }

    @Test void 이미지_입력스트림을_닫는다() throws Exception {
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        var file = new MockMultipartFile("files", "closed.jpg", "image/jpeg", encode(image(BufferedImage.TYPE_INT_RGB), "jpeg")) {
            @Override public InputStream getInputStream() throws IOException {
                opened.incrementAndGet();
                return new FilterInputStream(super.getInputStream()) {
                    private boolean done;
                    @Override public void close() throws IOException {
                        if (!done) { done = true; closed.incrementAndGet(); }
                        super.close();
                    }
                };
            }
        };
        storage.uploadFile(file);
        assertThat(opened.get()).isEqualTo(1);
        assertThat(closed.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"image/webp", "application/octet-stream"})
    void 재압축하지_않는_파일은_바이트를_유지한다(String mime) throws Exception {
        byte[] input = {82, 73, 70, 70, 1, 2, 3, 4, 87, 69, 66, 80};
        assertThat(upload(input, mime, "pass.webp")).isEqualTo(input);
    }

    private byte[] upload(byte[] input, String mime, String filename) throws IOException {
        String url = storage.uploadFile(new MockMultipartFile("files", filename, mime, input));
        assertThat(url).startsWith("https://storage.example.invalid/");
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(client).putObject(any(PutObjectRequest.class), body.capture());
        try (InputStream stream = body.getValue().contentStreamProvider().newStream()) { return stream.readAllBytes(); }
    }

    private static byte[] legacy(byte[] input) throws IOException {
        try (var stream = new ByteArrayInputStream(input); var out = new ByteArrayOutputStream()) {
            Thumbnails.of(stream).scale(1.0).outputQuality(0.9).outputFormat("JPEG").toOutputStream(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage image(int type) {
        BufferedImage image = new BufferedImage(40, 24, type);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 40; x++) {
            int alpha = type == BufferedImage.TYPE_INT_ARGB ? x * 255 / 39 : 255;
            image.setRGB(x, y, alpha << 24 | (x * 6) << 16 | (y * 10) << 8 | ((x + y) * 3));
        }
        return image;
    }

    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()); }
    private static byte[] encode(BufferedImage image, String format) throws IOException {
        try (var out = new ByteArrayOutputStream()) { assertThat(ImageIO.write(image, format, out)).isTrue(); return out.toByteArray(); }
        finally { image.flush(); }
    }

    private static byte[] withOrientation(byte[] jpeg, int orientation) throws IOException {
        // EXIF APP1 / little-endian TIFF / Orientation SHORT 항목 1개.
        ByteBuffer payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        payload.put(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I'}).putShort((short) 42).putInt(8);
        payload.putShort((short) 1).putShort((short) 0x112).putShort((short) 3).putInt(1);
        payload.putShort((short) orientation).putShort((short) 0).putInt(0);
        try (var out = new ByteArrayOutputStream()) {
            out.write(jpeg, 0, 2); out.write(new byte[]{(byte) 255, (byte) 225, 0, 34}); out.write(payload.array());
            out.write(jpeg, 2, jpeg.length - 2); return out.toByteArray();
        }
    }
}

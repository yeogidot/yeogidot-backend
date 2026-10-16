package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelDayRepository;
import com.yeogidot.yeogidot.security.OwnershipValidator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 별도 JVM에서 실행하는 테스트 전용 진입점. Spring 컨텍스트·DB·실제 S3 클라이언트는 생성하지 않는다.
 * 스트림·모의 호출을 latch로 잠시 멈춰 구조상의 동시 진입을 관찰한다. CPU·OOM 측정은 아니다.
 */
final class PhotoUploadConcurrencyProbe {
    private static final long WAIT_SECONDS = 10;
    private final PhotoRepository photos = mock(PhotoRepository.class);
    private final GcsService storage = mock(GcsService.class);
    private final GeoCodingService geocoding = mock(GeoCodingService.class);
    private final PhotoService service = new PhotoService(photos, mock(CommentRepository.class), storage, geocoding,
            new ObjectMapper(), mock(TravelDayRepository.class), new OwnershipValidator(), mock(R2DeletionTaskService.class));
    private final User owner = User.builder().id(1L).email("upload-test@example.invalid").password("unused").build();
    private final byte[] png;

    private PhotoUploadConcurrencyProbe() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", bytes)).isTrue();
            png = bytes.toByteArray();
        }
        when(photos.save(any(Photo.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("scenario result-path required");
        assertThat(ForkJoinPool.getCommonPoolParallelism()).isEqualTo(8);
        PhotoUploadConcurrencyProbe probe = new PhotoUploadConcurrencyProbe();
        Map<String, Object> evidence = switch (args[0]) {
            case "validation-outside-limit" -> probe.validationOutsideLimit();
            case "upload-limit" -> probe.uploadLimit();
            case "upload-failure-release" -> probe.failureRelease(false);
            case "postprocess-failure-release" -> probe.failureRelease(true);
            default -> throw new IllegalArgumentException("Unknown scenario");
        };
        evidence.put("scenario", args[0]);
        evidence.put("passed", true);
        evidence.put("externalServices", "mock-only");
        evidence.put("observedAtUtc", Instant.now().toString());
        evidence.put("javaRuntime", System.getProperty("java.runtime.version"));
        evidence.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        evidence.put("commonPoolParallelism", ForkJoinPool.getCommonPoolParallelism());
        evidence.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        evidence.put("fixture", Map.of("format", "PNG", "width", 2, "height", 2, "bytes", probe.png.length));
        evidence.put("scope", "controlled concurrency observation; not throughput, memory usage, OOM or real R2");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), evidence);
        System.out.println("UPLOAD_BASELINE_RESULT " + new ObjectMapper().writeValueAsString(evidence));
    }

    private Map<String, Object> validationOutsideLimit() throws Exception {
        Gate validation = new Gate(5);
        AtomicInteger uploads = new AtomicInteger();
        when(storage.uploadFile(any(MultipartFile.class))).thenAnswer(invocation -> {
            uploads.incrementAndGet();
            return url(invocation.getArgument(0));
        });
        ExecutorService callers = Executors.newFixedThreadPool(5);
        List<Future<List<Photo>>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                String name = "validation-" + i + ".png";
                MultipartFile observed = new MockMultipartFile("files", name, "image/png", png) {
                    @Override public InputStream getInputStream() {
                        // ImageIO.read()의 스트림 읽기 시작부터 try-with-resources 종료까지 관찰한다.
                        return new FilterInputStream(new ByteArrayInputStream(png)) {
                            private final AtomicBoolean entered = new AtomicBoolean();
                            private final AtomicBoolean closed = new AtomicBoolean();
                            private void enter() throws IOException {
                                if (entered.compareAndSet(false, true)) validation.enter();
                            }
                            @Override public int read() throws IOException { enter(); return super.read(); }
                            @Override public int read(byte[] b, int off, int len) throws IOException {
                                enter(); return in.read(b, off, len);
                            }
                            @Override public void close() throws IOException {
                                if (closed.compareAndSet(false, true)) {
                                    if (entered.get()) validation.leave();
                                    super.close();
                                }
                            }
                        };
                    }
                };
                requests.add(callers.submit(() -> upload(observed, false)));
            }
            validation.awaitEntered();
            assertThat(validation.active.get()).isEqualTo(5);
            assertThat(validation.peak.get()).isEqualTo(5);
            assertThat(uploads.get()).as("검증이 해제되기 전에는 업로드를 시작하지 않는다").isZero();
            validation.open();
            for (Future<List<Photo>> request : requests) assertThat(get(request)).hasSize(1);
            assertThat(validation.active.get()).isZero();
            assertThat(uploads.get()).isEqualTo(5);
            verify(photos, times(5)).save(any(Photo.class));
            verifyNoInteractions(geocoding);
            return evidence("requestCount", 5, "validationStreamPeak", validation.peak.get(), "uploadsBeforeValidationRelease", 0,
                    "completedRequests", 5, "observation", "real ImageIO validation stream reads held by a test latch");
        } finally {
            validation.open();
            shutdown(callers);
        }
    }

    private Map<String, Object> uploadLimit() throws Exception {
        Gate upload = new Gate(3);
        AtomicInteger calls = new AtomicInteger();
        when(storage.uploadFile(any(MultipartFile.class))).thenAnswer(invocation -> {
            calls.incrementAndGet();
            upload.enter();
            try { return url(invocation.getArgument(0)); }
            finally { upload.leave(); }
        });
        ExecutorService callers = Executors.newFixedThreadPool(5);
        List<Future<List<Photo>>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                MultipartFile file = file("limit-" + i + ".png");
                requests.add(callers.submit(() -> upload(file, false)));
            }
            upload.awaitEntered();
            int waiting = awaitSemaphoreWaiters(2);
            assertThat(calls.get()).as("3개를 잡아 둔 동안 나머지 요청은 업로드에 진입할 수 없다").isEqualTo(3);
            assertThat(upload.active.get()).isEqualTo(3);
            upload.open();
            for (Future<List<Photo>> request : requests) assertThat(get(request)).hasSize(1);
            assertThat(calls.get()).isEqualTo(5);
            assertThat(upload.peak.get()).isEqualTo(3);
            assertThat(upload.active.get()).isZero();
            verify(photos, times(5)).save(any(Photo.class));
            verifyNoInteractions(geocoding);
            return evidence("requestCount", 5, "uploadPeak", upload.peak.get(), "observedSemaphoreWaiters", waiting,
                    "uploadsBeforeRelease", 3, "completedRequests", 5);
        } finally {
            upload.open();
            shutdown(callers);
        }
    }

    private Map<String, Object> failureRelease(boolean postprocess) throws Exception {
        Gate failures = new Gate(3);
        Gate followUp = new Gate(3);
        AtomicInteger calls = new AtomicInteger();
        when(storage.uploadFile(any(MultipartFile.class))).thenAnswer(invocation -> {
            MultipartFile file = invocation.getArgument(0);
            calls.incrementAndGet();
            if (file.getOriginalFilename().startsWith("failed-")) {
                if (postprocess) return url(file);
                failures.enter();
                try { throw new IOException("forced-upload-failure"); }
                finally { failures.leave(); }
            }
            followUp.enter();
            try { return url(file); }
            finally { followUp.leave(); }
        });
        if (postprocess) {
            when(geocoding.getDistrictFromCoordinates(any(), any())).thenAnswer(invocation -> {
                failures.enter();
                try { throw new IllegalStateException("forced-postprocess-failure"); }
                finally { failures.leave(); }
            });
        }
        ExecutorService callers = Executors.newFixedThreadPool(3);
        List<Future<List<Photo>>> initial = new ArrayList<>();
        List<Future<List<Photo>>> next = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                MultipartFile file = file("failed-" + i + ".png");
                initial.add(callers.submit(() -> upload(file, postprocess)));
            }
            failures.awaitEntered();
            assertThat(failures.peak.get()).isEqualTo(3);
            failures.open();
            for (Future<List<Photo>> request : initial) {
                try { get(request); throw new AssertionError("Expected the injected failure"); }
                catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
                    assertThat(exception.getCause()).hasStackTraceContaining(postprocess ? "forced-postprocess-failure" : "forced-upload-failure");
                }
            }
            verify(photos, never()).save(any());
            verify(storage, times(postprocess ? 3 : 0)).deleteFile(any());
            for (int i = 0; i < 3; i++) {
                MultipartFile file = file("follow-up-" + i + ".png");
                next.add(callers.submit(() -> upload(file, false)));
            }
            // 다음 요청 3개가 동시에 진입해야 모든 처리 권한이 반환됐다고 판단한다.
            followUp.awaitEntered();
            assertThat(followUp.active.get()).isEqualTo(3);
            followUp.open();
            for (Future<List<Photo>> request : next) assertThat(get(request)).hasSize(1);
            assertThat(calls.get()).isEqualTo(6);
            assertThat(followUp.peak.get()).isEqualTo(3);
            assertThat(followUp.active.get()).isZero();
            verify(photos, times(3)).save(any(Photo.class));
            verify(storage, times(postprocess ? 3 : 0)).deleteFile(any());
            return evidence("failedRequests", 3, "failureStage", postprocess ? "geocoding-after-upload" : "upload-IOException",
                    "failurePeak", failures.peak.get(), "followUpCompletedRequests", 3, "followUpUploadPeak", followUp.peak.get(),
                    "compensationDeleteCalls", postprocess ? 3 : 0);
        } finally {
            failures.open();
            followUp.open();
            shutdown(callers);
        }
    }

    private List<Photo> upload(MultipartFile file, boolean coordinates) throws IOException {
        String metadata = "[{\"originalName\":\"" + file.getOriginalFilename() + "\",\"takenAt\":\"2026-10-16T10:00:00\""
                + (coordinates ? ",\"latitude\":37.5665,\"longitude\":126.9780" : "") + "}]";
        return service.uploadPhotos(List.of(file), metadata, owner);
    }

    private MultipartFile file(String name) { return new MockMultipartFile("files", name, "image/png", png); }
    private static String url(MultipartFile file) { return "https://storage.example.invalid/" + file.getOriginalFilename(); }
    private static List<Photo> get(Future<List<Photo>> future) throws Exception { return future.get(15, TimeUnit.SECONDS); }

    private static void shutdown(ExecutorService callers) throws InterruptedException {
        callers.shutdown();
        if (!callers.awaitTermination(15, TimeUnit.SECONDS)) {
            callers.shutdownNow();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).as("호출 스레드 정리").isTrue();
        }
    }

    private static int awaitSemaphoreWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = 0;
            for (var info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
                boolean serviceFrame = false;
                boolean semaphoreFrame = false;
                for (StackTraceElement frame : info.getStackTrace()) {
                    serviceFrame |= frame.getClassName().equals(PhotoService.class.getName()) && frame.getMethodName().startsWith("lambda$uploadPhotos$");
                    semaphoreFrame |= frame.getClassName().equals("java.util.concurrent.Semaphore") && frame.getMethodName().equals("acquire");
                }
                if (serviceFrame && semaphoreFrame) waiting++;
            }
            if (waiting >= expected) return waiting;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Expected semaphore waiters=" + expected + ", observed=" + waiting);
    }

    private static Map<String, Object> evidence(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static final class Gate {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private Gate(int expected) { entered = new CountDownLatch(expected); }
        private void enter() throws IOException {
            int current = active.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) throw new IOException("Test gate timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Test gate interrupted", exception);
            }
        }
        private void leave() { active.decrementAndGet(); }
        private void open() { release.countDown(); }
        private void awaitEntered() throws InterruptedException {
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).as("테스트 진입 지점 도달").isTrue();
        }
    }
}

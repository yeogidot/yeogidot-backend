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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 실제 검증·압축을 실행한다. Spring, HTTP, JDBC, 실제 S3 클라이언트는 생성하지 않는다. */
final class PhotoUploadResourceProcess {
    static final int WARMUP_ROUNDS = 1;
    static final int MEASURED_ROUNDS = 3;
    static final long SAMPLE_MS = 5;
    static final long MOCK_UPLOAD_MS = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ThreadLocal<FileStat> uploading = new ThreadLocal<>();
    private final AtomicInteger validationActive = new AtomicInteger();
    private final AtomicInteger uploadActive = new AtomicInteger();
    private final AtomicInteger readyFiles = new AtomicInteger();
    private final AtomicInteger uploadEntries = new AtomicInteger();
    private final AtomicInteger savedPhotos = new AtomicInteger();
    private final PhotoService service;

    private PhotoUploadResourceProcess() {
        PhotoRepository repository = (PhotoRepository) Proxy.newProxyInstance(PhotoRepository.class.getClassLoader(),
                new Class<?>[]{PhotoRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> { savedPhotos.incrementAndGet(); yield args[0]; }
                    case "flush" -> null;
                    case "toString" -> "NoDatabasePhotoRepository";
                    default -> throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
                });
        // Mockito의 호출 이력에 RequestBody가 남아 힙 사용량을 왜곡하지 않도록 기록하지 않는 프록시를 쓴다.
        S3Client s3 = (S3Client) Proxy.newProxyInstance(S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "putObject" -> {
                        if (args.length != 2 || !(args[1] instanceof RequestBody body)) throw new AssertionError("Unexpected upload overload");
                        FileStat stat = uploading.get();
                        if (stat == null) throw new AssertionError("Upload stage missing");
                        stat.compressionSetupNs = System.nanoTime() - stat.uploadStartNs;
                        stat.encodedBytes = body.optionalContentLength().orElseThrow();
                        try (InputStream encoded = body.contentStreamProvider().newStream()) {
                            if (encoded.read() != 0xff || encoded.read() != 0xd8) throw new AssertionError("Expected real JPEG compression output");
                        }
                        long start = System.nanoTime();
                        long until = start + TimeUnit.MILLISECONDS.toNanos(MOCK_UPLOAD_MS);
                        while (System.nanoTime() < until) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Mock upload interrupted");
                            LockSupport.parkNanos(until - System.nanoTime());
                        }
                        stat.mockUploadNs = System.nanoTime() - start;
                        yield PutObjectResponse.builder().eTag("local-benchmark-only").build();
                    }
                    case "deleteObject" -> DeleteObjectResponse.builder().build();
                    case "close" -> null;
                    case "serviceName" -> "local-benchmark-only";
                    case "toString" -> "NoNetworkS3Client";
                    default -> throw new UnsupportedOperationException("Unexpected S3 call: " + method.getName());
                });
        GcsService storage = new GcsService(s3) {
            @Override public String uploadFile(MultipartFile file) throws IOException {
                TrackedFile tracked = (TrackedFile) file;
                FileStat stat = tracked.stat;
                stat.uploadStartNs = System.nanoTime();
                stat.afterValidationUntilUploadNs = stat.uploadStartNs - stat.request.validationDoneNs;
                uploadEntries.incrementAndGet();
                uploadActive.incrementAndGet();
                uploading.set(stat);
                var stage = PhotoUploadAllocationProfiler.begin("upload", stat.request.round, stat.request.index, tracked.name);
                try { return super.uploadFile(file); }
                finally { PhotoUploadAllocationProfiler.finish(stage); uploading.remove(); uploadActive.decrementAndGet(); }
            }
        };
        ReflectionTestUtils.setField(storage, "bucketName", "local-benchmark-not-a-real-bucket");
        ReflectionTestUtils.setField(storage, "publicUrl", "https://storage.example.invalid");
        service = new PhotoService(repository, mock(CommentRepository.class), storage, mock(GeoCodingService.class),
                JSON, mock(TravelDayRepository.class), new OwnershipValidator(), mock(R2DeletionTaskService.class));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("mode fixture-directory output-json required");
        if (args[0].equals("fixtures") || args[0].equals("smoke-fixture")) {
            prepareFixtures(Path.of(args[1]), args[0].equals("smoke-fixture"));
            return;
        }
        String[] parts = args[0].split("-");
        if (parts.length != 3) throw new IllegalArgumentException("Expected profile-files-concurrency");
        String profile = parts[0];
        int files = Integer.parseInt(parts[1]), concurrency = Integer.parseInt(parts[2]);
        if (!List.of("standard", "large", "smoke").contains(profile) || !List.of(1, 10).contains(files)
                || !List.of(1, 3, 5).contains(concurrency)) throw new IllegalArgumentException("Unsafe benchmark condition");
        Path fixture = Path.of(args[1]).resolve(profile + ".jpg");
        PhotoUploadResourceProcess process = new PhotoUploadResourceProcess();
        assertThat(ForkJoinPool.getCommonPoolParallelism()).isEqualTo(8);
        Map<String, Object> evidence;
        try (var allocationRecording = PhotoUploadAllocationProfiler.startIfConfigured()) {
            evidence = process.measure(args[0], fixture, files, concurrency);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), evidence);
        System.out.println("UPLOAD_RESOURCE_RESULT " + JSON.writeValueAsString(evidence.get("summary")));
    }

    @SuppressWarnings("unchecked") // requests에는 이 클래스에서 생성한 Map<String,Object> 목록만 들어간다.
    private Map<String, Object> measure(String scenario, Path fixture, int files, int concurrency) throws Exception {
        List<Map<String, Object>> rounds = new ArrayList<>();
        List<Map<String, Object>> warmups = new ArrayList<>();
        ExecutorService callers = Executors.newFixedThreadPool(concurrency);
        try {
            for (int i = 0; i < WARMUP_ROUNDS; i++) warmups.add(runRound(callers, fixture, files, concurrency, -1));
            for (int i = 0; i < MEASURED_ROUNDS; i++) rounds.add(runRound(callers, fixture, files, concurrency, i + 1));
        } finally {
            callers.shutdownNow();
            if (!callers.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("Caller threads did not stop");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", scenario);
        result.put("observedAtUtc", Instant.now().toString());
        result.put("fixture", JSON.readTree(fixture.getParent().resolve("fixtures.json").toFile()).get(fixture.getFileName().toString()));
        result.put("javaRuntime", System.getProperty("java.runtime.version"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        result.put("collectors", ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList());
        result.put("commonPoolParallelism", ForkJoinPool.getCommonPoolParallelism());
        result.put("warmupRounds", WARMUP_ROUNDS); result.put("measuredRounds", MEASURED_ROUNDS);
        result.put("filesPerRequest", files); result.put("concurrentRequests", concurrency);
        result.put("requestedSamplingMs", SAMPLE_MS); result.put("configuredMockUploadMs", MOCK_UPLOAD_MS);
        result.put("externalServices", "no-network-no-database; actual ImageIO and GcsService compression; null coordinates");
        result.put("imageIoDiskCacheEnabled", ImageIO.getUseCache());
        result.put("timingScope", "plain PhotoService call; excludes HTTP/multipart receipt, authentication, actual DB/transaction commit and real R2/Kakao");
        result.put("waitingScope", "last validation stream close to GcsService entry; includes metadata parsing, submission, scheduling and semaphore wait");
        result.put("heapScope", "sampled JVM heap used including uncollected garbage; not RSS, exact allocation or per-image memory");
        result.put("rounds", rounds);
        if (PhotoUploadAllocationProfiler.enabled()) {
            result.put("allocationDiagnostic", "JFR enabled; separate diagnostic, do not compare timing with unprofiled baseline");
            result.put("diagnosticWarmups", warmups);
        }
        List<Double> latencies = rounds.stream().flatMap(r -> ((List<Map<String, Object>>) r.get("requests")).stream())
                .map(r -> ((Number)r.get("serviceMs")).doubleValue()).toList();
        List<Map<String, Object>> requestRows = rounds.stream()
                .flatMap(r -> ((List<Map<String, Object>>) r.get("requests")).stream()).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requestSamples", latencies.size());
        summary.put("successfulRequests", rounds.stream().mapToInt(r -> ((Number)r.get("successes")).intValue()).sum());
        summary.put("failedRequests", rounds.stream().mapToInt(r -> ((Number)r.get("failures")).intValue()).sum());
        summary.put("serviceMedianMs", median(latencies));
        summary.put("serviceMinMs", latencies.stream().mapToDouble(Double::doubleValue).min().orElseThrow());
        summary.put("serviceMaxMs", latencies.stream().mapToDouble(Double::doubleValue).max().orElseThrow());
        // 빠르게 실패한 요청을 정상 완료 시간으로 해석하지 않도록 별도 집계한다.
        summary.put("latencyScope", "serviceMedian/Min/MaxMs include success and failure; successfulServiceMedianMs excludes failures");
        summary.put("successfulServiceMedianMs", successfulMedian(requestRows));
        summary.put("observedHeapPeakBytes", rounds.stream().mapToLong(r -> ((Number)r.get("heapPeakBytes")).longValue()).max().orElseThrow());
        summary.put("gcCollectionTimeMs", rounds.stream().mapToLong(r -> ((Number)r.get("gcCollectionTimeMs")).longValue()).sum());
        summary.put("gcCollections", rounds.stream().mapToLong(r -> ((Number)r.get("gcCollections")).longValue()).sum());
        summary.put("validationPeak", rounds.stream().mapToInt(r -> ((Number)r.get("validationPeak")).intValue()).max().orElseThrow());
        summary.put("uploadPeak", rounds.stream().mapToInt(r -> ((Number)r.get("uploadPeak")).intValue()).max().orElseThrow());
        summary.put("postValidationPendingPeak", rounds.stream().mapToInt(r -> ((Number)r.get("postValidationPendingPeak")).intValue()).max().orElseThrow());
        result.put("summary", summary);
        return result;
    }

    private Map<String, Object> runRound(ExecutorService callers, Path fixture, int files, int concurrency, int round) throws Exception {
        readyFiles.set(0); uploadEntries.set(0); savedPhotos.set(0);
        assertThat(validationActive.get()).isZero(); assertThat(uploadActive.get()).isZero();
        List<RequestStat> requests = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            RequestStat request = new RequestStat(round, i, files);
            for (int j = 0; j < files; j++) request.files.add(new TrackedFile(fixture, "photo-" + i + "-" + j + ".jpg", new FileStat(request)));
            request.metadata = JSON.writeValueAsString(request.files.stream().map(f -> Map.of("originalName", f.name, "takenAt", "2026-10-16T10:00:00")).toList());
            requests.add(request);
        }
        CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (RequestStat request : requests) futures.add(callers.submit(() -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start gate timed out");
                long begin = System.nanoTime();
                try {
                    List<Photo> result = service.uploadPhotos(new ArrayList<>(request.files), request.metadata,
                            User.builder().id(1L).email("benchmark@example.invalid").password("unused").build());
                    if (result.size() != files) throw new AssertionError("Unexpected photo count");
                    request.success = true;
                } catch (Throwable failure) {
                    Throwable root = failure;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    request.failure = root.getClass().getSimpleName();
                    request.failureDetail = root.toString();
                    request.failureStack = java.util.Arrays.stream(root.getStackTrace()).limit(16).map(Object::toString).toList();
                } finally { request.serviceNs = System.nanoTime() - begin; }
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
        }));
        if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Callers not ready");
        long gcBefore = gcTime(), countBefore = gcCount();
        HeapSampler sampler = new HeapSampler();
        long begin = System.nanoTime();
        sampler.start(); start.countDown();
        try {
            for (Future<?> future : futures) future.get(120, TimeUnit.SECONDS);
        } finally { start.countDown(); sampler.close(); }
        long duration = System.nanoTime() - begin;
        long gcDelta = gcTime() - gcBefore, countDelta = gcCount() - countBefore;
        List<Map<String, Object>> requestRows = new ArrayList<>();
        List<Map<String, Object>> fileRows = new ArrayList<>();
        int successes = 0;
        for (RequestStat request : requests) {
            if (request.success) successes++;
            requestRows.add(Map.of("request", request.index, "serviceMs", ms(request.serviceNs), "success", request.success,
                    "failure", request.failure, "failureDetail", request.failureDetail, "failureStack", request.failureStack,
                    "validationSumMs", ms(request.validationSumNs.get())));
            for (TrackedFile file : request.files) {
                FileStat stat = file.stat;
                fileRows.add(Map.of("request", request.index, "file", file.name, "validationMs", ms(stat.validationNs),
                        "afterValidationUntilUploadMs", ms(stat.afterValidationUntilUploadNs), "compressionAndSetupMs", ms(stat.compressionSetupNs),
                        "mockUploadMs", ms(stat.mockUploadNs), "encodedBytes", stat.encodedBytes, "inputStreamOpens", file.opens.get()));
                file.closeStreams();
            }
        }
        assertThat(uploadActive.get()).isZero(); assertThat(validationActive.get()).isZero();
        assertThat(savedPhotos.get()).isEqualTo(successes * files);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("round", round); row.put("successes", successes); row.put("failures", concurrency - successes);
        row.put("makespanMs", ms(duration)); row.put("completedRequestsPerSecond", successes / (duration / 1_000_000_000.0));
        row.put("heapStartBytes", sampler.initial); row.put("heapPeakBytes", sampler.peak.get()); row.put("heapEndBytes", sampler.last);
        row.put("heapSamples", sampler.samples); row.put("maxSamplingGapMs", ms(sampler.maxGap));
        row.put("gcCollectionTimeMs", gcDelta); row.put("gcCollections", countDelta);
        row.put("validationPeak", sampler.validationPeak); row.put("uploadPeak", sampler.uploadPeak);
        row.put("postValidationPendingPeak", sampler.pendingPeak); row.put("commonPoolQueuedPeak", sampler.queuedPeak);
        row.put("requests", requestRows); row.put("files", fileRows);
        if (round < 0 && successes != concurrency && !PhotoUploadAllocationProfiler.enabled()) throw new AssertionError("Warmup failed: " + requestRows);
        return row;
    }

    private final class TrackedFile implements MultipartFile {
        private final Path path; private final String name; private final FileStat stat;
        private final AtomicInteger opens = new AtomicInteger();
        private final List<InputStream> streams = new CopyOnWriteArrayList<>();
        TrackedFile(Path path, String name, FileStat stat) { this.path = path; this.name = name; this.stat = stat; }
        public String getName() { return "files"; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return "image/jpeg"; }
        public boolean isEmpty() { return false; }
        public long getSize() { try { return Files.size(path); } catch (IOException e) { throw new RuntimeException(e); } }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public void transferTo(File file) { throw new UnsupportedOperationException("Not used by baseline"); }
        public InputStream getInputStream() throws IOException {
            boolean validation = opens.getAndIncrement() == 0;
            InputStream original = Files.newInputStream(path);
            long begin = System.nanoTime();
            var stage = validation ? PhotoUploadAllocationProfiler.begin("validation", stat.request.round, stat.request.index, name) : null;
            if (validation) validationActive.incrementAndGet();
            InputStream tracked = new FilterInputStream(original) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (closed) return; closed = true;
                    try { super.close(); }
                    finally {
                        PhotoUploadAllocationProfiler.finish(stage);
                        if (validation) {
                            stat.validationNs = System.nanoTime() - begin;
                            stat.request.validationSumNs.addAndGet(stat.validationNs);
                            validationActive.decrementAndGet();
                            if (stat.request.validated.incrementAndGet() == stat.request.expectedFiles) {
                                stat.request.validationDoneNs = System.nanoTime();
                                readyFiles.addAndGet(stat.request.expectedFiles);
                            }
                        }
                    }
                }
            };
            streams.add(tracked);
            return tracked;
        }
        void closeStreams() throws IOException { for (InputStream stream : streams) stream.close(); streams.clear(); }
    }

    private final class HeapSampler implements AutoCloseable {
        private final AtomicLong peak = new AtomicLong();
        private volatile boolean running = true;
        private long initial, last, samples, previous, maxGap, queuedPeak;
        private int validationPeak, uploadPeak, pendingPeak;
        private final Thread thread = new Thread(() -> {
            while (running) { sample(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SAMPLE_MS)); }
        }, "upload-heap-sampler");
        void start() { initial = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); sample(); thread.setDaemon(true); thread.start(); }
        private void sample() {
            long now = System.nanoTime();
            if (previous != 0) maxGap = Math.max(maxGap, now - previous);
            previous = now; samples++;
            last = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); peak.accumulateAndGet(last, Math::max);
            validationPeak = Math.max(validationPeak, validationActive.get()); uploadPeak = Math.max(uploadPeak, uploadActive.get());
            pendingPeak = Math.max(pendingPeak, Math.max(0, readyFiles.get() - uploadEntries.get()));
            queuedPeak = Math.max(queuedPeak, ForkJoinPool.commonPool().getQueuedSubmissionCount() + ForkJoinPool.commonPool().getQueuedTaskCount());
        }
        public void close() throws InterruptedException { running = false; thread.interrupt(); thread.join(3000); if (thread.isAlive()) throw new AssertionError("Sampler did not stop"); sample(); }
    }

    private static final class RequestStat {
        final int round, index, expectedFiles; final AtomicInteger validated = new AtomicInteger(); final AtomicLong validationSumNs = new AtomicLong();
        final List<TrackedFile> files = new ArrayList<>();
        String metadata; volatile long validationDoneNs, serviceNs; volatile boolean success; volatile String failure = "";
        volatile String failureDetail = ""; volatile List<String> failureStack = List.of();
        RequestStat(int round, int index, int expectedFiles) { this.round = round; this.index = index; this.expectedFiles = expectedFiles; }
    }
    private static final class FileStat {
        final RequestStat request; volatile long validationNs, uploadStartNs, afterValidationUntilUploadNs, compressionSetupNs, mockUploadNs, encodedBytes;
        FileStat(RequestStat request) { this.request = request; }
    }

    private static double ms(long ns) { return ns / 1_000_000.0; }
    static double median(List<Double> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("No samples");
        List<Double> sorted = values.stream().sorted().toList(); int n = sorted.size();
        return n % 2 == 0 ? (sorted.get(n/2-1) + sorted.get(n/2))/2 : sorted.get(n/2);
    }
    static Double successfulMedian(List<Map<String, Object>> requests) {
        List<Double> values = requests.stream().filter(r -> Boolean.TRUE.equals(r.get("success")))
                .map(r -> ((Number) r.get("serviceMs")).doubleValue()).toList();
        return values.isEmpty() ? null : median(values);
    }
    private static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum(); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0,b.getCollectionCount())).sum(); }

    private static void prepareFixtures(Path directory, boolean smoke) throws Exception {
        Files.createDirectories(directory);
        Map<String, Object> manifest = new LinkedHashMap<>();
        for (String profile : smoke ? List.of("smoke") : List.of("standard", "large")) {
            int width = profile.equals("large") ? 4000 : profile.equals("standard") ? 1600 : 64;
            int height = profile.equals("large") ? 3000 : profile.equals("standard") ? 1200 : 48;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Random random = new Random(20261016L); int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int noise = random.nextInt(64);
                    int red = (x * 191 / width + noise) & 255, green = (y * 191 / height + noise) & 255;
                    row[x] = (red << 16) | (green << 8) | ((x + y + noise) & 255);
                }
                image.setRGB(0,y,width,1,row,0,width);
            }
            Path file = directory.resolve(profile + ".jpg");
            var writer = ImageIO.getImageWritersByFormatName("JPEG").next();
            try (var output = ImageIO.createImageOutputStream(file.toFile())) {
                writer.setOutput(output);
                var parameter = writer.getDefaultWriteParam(); parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameter.setCompressionQuality(.92f);
                writer.write(null,new IIOImage(image,null,null),parameter);
            } finally { writer.dispose(); image.flush(); }
            manifest.put(file.getFileName().toString(), Map.of("width",width,"height",height,"format","JPEG","bytes",Files.size(file),
                    "sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),
                    "generator","seeded gradient+noise, seed 20261016, JPEG quality 0.92; not phone photographs"));
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("fixtures.json").toFile(), manifest);
    }
}

package com.yeogidot.yeogidot.service.photo.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 로컬 진단 전용. 할당 기록을 생존 메모리나 순간 최대 사용량으로 해석하지 않는다. */
final class PhotoUploadAllocationProfiler implements AutoCloseable {
    static final String PROPERTY = "upload.resource.allocationRecording";
    static final String STAGE_EVENT = "yeogidot.UploadResourceStage";
    static final long LARGE_BYTES = 1024 * 1024;
    private static volatile boolean active;
    private final Recording recording;
    private final Path destination;

    private PhotoUploadAllocationProfiler(Recording recording, Path destination) {
        this.recording = recording;
        this.destination = destination;
    }

    static PhotoUploadAllocationProfiler startIfConfigured() {
        String target = System.getProperty(PROPERTY);
        return target == null ? new PhotoUploadAllocationProfiler(null, null) : start(Path.of(target));
    }

    static PhotoUploadAllocationProfiler start(Path destination) {
        if (active) throw new IllegalStateException("An upload allocation recording is already active");
        Recording recording = new Recording();
        recording.setName("local-upload-allocation-diagnostic");
        recording.setToDisk(true);
        recording.setMaxSize(64L * 1024 * 1024);
        recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
        recording.enable("jdk.GCHeapSummary");
        recording.enable("jdk.GarbageCollection").withThreshold(Duration.ZERO);
        recording.enable("jdk.DataLoss");
        recording.enable(Stage.class).withoutStackTrace().withThreshold(Duration.ZERO);
        recording.start();
        active = true;
        return new PhotoUploadAllocationProfiler(recording, destination);
    }

    static boolean enabled() { return active; }

    static Stage begin(String phase, int round, int request, String file) {
        if (!active) return null;
        Stage stage = new Stage();
        stage.phase = phase;
        stage.round = round;
        stage.request = request;
        stage.file = file;
        stage.begin();
        return stage;
    }

    static void finish(Stage stage) {
        if (stage != null) { stage.end(); stage.commit(); }
    }

    @Override public void close() throws IOException {
        if (recording == null) return;
        active = false;
        try { recording.stop(); recording.dump(destination); }
        finally { recording.close(); }
    }

    @Name(STAGE_EVENT) @Label("Upload resource stage") @Category("Local upload diagnostic") @StackTrace(false)
    static final class Stage extends Event {
        @Label("Phase") String phase;
        @Label("Round (-1 is warmup)") int round;
        @Label("Request") int request;
        @Label("Fixture file") String file;
    }

    static Map<String, Object> analyze(Path input) throws IOException {
        List<RecordedEvent> events = RecordingFile.readAllEvents(input);
        List<RecordedEvent> stages = events.stream().filter(e -> e.getEventType().getName().equals(STAGE_EVENT)).toList();
        Map<String, AllocationGroup> groups = new LinkedHashMap<>();
        List<Map<String, Object>> allocations = new ArrayList<>();
        long allOutside = 0, unattributedLarge = 0, lostBytes = 0, heapObserved = 0;
        int gcEvents = 0;
        for (RecordedEvent event : events) {
            String type = event.getEventType().getName();
            if (type.equals("jdk.DataLoss")) lostBytes += event.getLong("amount");
            if (type.equals("jdk.GarbageCollection")) gcEvents++;
            if (type.equals("jdk.GCHeapSummary")) heapObserved = Math.max(heapObserved, event.getLong("heapUsed"));
            if (!type.equals("jdk.ObjectAllocationOutsideTLAB")) continue;
            allOutside++;
            long bytes = event.getLong("allocationSize");
            if (bytes < LARGE_BYTES) continue;
            RecordedEvent owner = stages.stream().filter(stage -> contains(stage, event)).findFirst().orElse(null);
            if (owner == null) { unattributedLarge++; continue; }
            List<String> stack = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames().stream()
                    .map(frame -> frame.getMethod().getType().getName() + "." + frame.getMethod().getName()
                            + ":" + frame.getLineNumber()).toList();
            String category = classify(owner.getString("phase"), stack);
            String objectClass = event.getClass("objectClass").getName();
            String key = category + "|" + objectClass + "|" + bytes;
            AllocationGroup group = groups.computeIfAbsent(key, ignored -> new AllocationGroup(category, objectClass, bytes, stack));
            group.count++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", event.getStartTime().toString()); row.put("thread", event.getThread().getJavaThreadId());
            row.put("round", owner.getInt("round")); row.put("request", owner.getInt("request"));
            row.put("file", owner.getString("file")); row.put("phase", owner.getString("phase"));
            row.put("category", category); row.put("class", objectClass); row.put("bytes", bytes);
            allocations.add(row);
        }
        List<Map<String, Object>> groupRows = groups.values().stream()
                .sorted(Comparator.comparingLong((AllocationGroup g) -> g.bytes * g.count).reversed())
                .map(AllocationGroup::row).toList();
        List<Map<String, Object>> stageRows = new ArrayList<>();
        List<Boundary> boundaries = new ArrayList<>();
        for (RecordedEvent stage : stages) {
            stageRows.add(Map.of("phase", stage.getString("phase"), "round", stage.getInt("round"),
                    "request", stage.getInt("request"), "file", stage.getString("file"),
                    "thread", stage.getThread().getJavaThreadId(), "start", stage.getStartTime().toString(),
                    "end", stage.getEndTime().toString()));
            boundaries.add(new Boundary(stage.getStartTime(), stage.getString("phase"), 1));
            boundaries.add(new Boundary(stage.getEndTime(), stage.getString("phase"), -1));
        }
        boundaries.sort(Comparator.comparing(Boundary::at).thenComparingInt(Boundary::delta));
        int validation = 0, upload = 0, validationPeak = 0, uploadPeak = 0;
        boolean overlap = false;
        for (Boundary boundary : boundaries) {
            if (boundary.phase.equals("validation")) validation += boundary.delta;
            else upload += boundary.delta;
            validationPeak = Math.max(validationPeak, validation); uploadPeak = Math.max(uploadPeak, upload);
            overlap |= validation > 0 && upload > 0;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scope", "diagnostic, includes warmup; successful outside-TLAB allocation events >=1MiB attributed by same-thread stage interval; NOT live memory, total allocations, failed allocation size or a performance comparison");
        summary.put("allOutsideTlabEvents", allOutside); summary.put("stageEvents", stages.size());
        summary.put("unattributedLargeEvents", unattributedLarge); summary.put("dataLossBytes", lostBytes);
        summary.put("attributedLargeEvents", allocations.size());
        summary.put("attributedLargeCumulativeBytes", allocations.stream().mapToLong(row -> (long) row.get("bytes")).sum());
        summary.put("gcHeapSummaryObservedMaxBytes", heapObserved); summary.put("gcEvents", gcEvents);
        summary.put("validationIntervalPeak", validationPeak); summary.put("uploadIntervalPeak", uploadPeak);
        summary.put("validationAndUploadOverlap", overlap);
        summary.put("groups", groupRows); summary.put("allocations", allocations); summary.put("stages", stageRows);
        return summary;
    }

    private static boolean contains(RecordedEvent stage, RecordedEvent allocation) {
        return stage.getThread().getJavaThreadId() == allocation.getThread().getJavaThreadId()
                && !allocation.getStartTime().isBefore(stage.getStartTime())
                && !allocation.getStartTime().isAfter(stage.getEndTime());
    }

    static String classify(String phase, List<String> stack) {
        if (phase.equals("validation")) return "validation-image-read";
        if (has(stack, "software.amazon.awssdk.core.sync.RequestBody.fromBytes")) return "sdk-request-body-copy";
        if (has(stack, "net.coobird.thumbnailator.util.BufferedImages.copy")) return "jpeg-output-rgb-copy";
        if (has(stack, "java.io.ByteArrayOutputStream.toByteArray")) return "encoded-result-copy";
        if (has(stack, "java.io.ByteArrayOutputStream.ensureCapacity")) return "encoded-buffer-growth";
        if (has(stack, "com.sun.imageio.plugins.jpeg.JPEGImageReader")) return "compression-image-read";
        if (has(stack, "net.coobird.thumbnailator.resizers") || has(stack, "net.coobird.thumbnailator.makers")) return "thumbnail-image-build";
        return "other-upload-stage";
    }

    private static boolean has(List<String> stack, String part) { return stack.stream().anyMatch(s -> s.contains(part)); }
    private record Boundary(Instant at, String phase, int delta) {}
    private static final class AllocationGroup {
        final String category, objectClass; final long bytes; final List<String> stack; long count;
        AllocationGroup(String category, String objectClass, long bytes, List<String> stack) {
            this.category = category; this.objectClass = objectClass; this.bytes = bytes; this.stack = stack;
        }
        Map<String, Object> row() {
            return Map.of("category", category, "class", objectClass, "singleAllocationBytes", bytes,
                    "events", count, "cumulativeBytes", bytes * count, "representativeStack", stack);
        }
    }

    static void writeAnalysis(Path recording, Path output) throws IOException {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), analyze(recording));
    }
}

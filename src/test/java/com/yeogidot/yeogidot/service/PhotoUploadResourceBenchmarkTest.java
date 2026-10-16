package com.yeogidot.yeogidot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PhotoUploadResourceBenchmarkTest {
    @TempDir static Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN_ID = Instant.now().toString().replaceAll("[^0-9TZ]", "") + "-" + UUID.randomUUID().toString().substring(0,8);
    private static final Path REPORT = Path.of("build", "reports", "upload-resource-baseline", RUN_ID).toAbsolutePath();

    @Test @Order(1)
    void 작은_이미지로_실제_검증과_압축_계측을_검사한다() throws Exception {
        Path fixtures = directory.resolve("smoke-fixtures");
        run("smoke-fixture", fixtures, "smoke-fixtures", 256);
        JsonNode result = run("smoke-1-1", fixtures, "smoke", 512);
        assertThat(result.path("summary").path("requestSamples").asInt()).isEqualTo(3);
        assertThat(result.path("summary").path("failedRequests").asInt()).isZero();
        for (JsonNode round : result.path("rounds")) for (JsonNode file : round.path("files")) {
            assertThat(file.path("inputStreamOpens").asInt()).isEqualTo(2);
            assertThat(file.path("validationMs").asDouble()).isPositive();
            assertThat(file.path("compressionAndSetupMs").asDouble()).isPositive();
            assertThat(file.path("encodedBytes").asLong()).isPositive();
            assertThat(file.path("mockUploadMs").asDouble()).isGreaterThanOrEqualTo(50);
        }
    }

    @Test @Order(1)
    void 중앙값은_정렬된_실제_표본으로_계산한다() {
        assertThat(PhotoUploadResourceProcess.median(List.of(9.0,1.0,3.0))).isEqualTo(3);
        assertThat(PhotoUploadResourceProcess.median(List.of(9.0,1.0,3.0,5.0))).isEqualTo(4);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> PhotoUploadResourceProcess.median(List.of()));
    }

    @Test @Order(1)
    void 실패한_요청의_시간을_정상완료_중앙값에_포함하지_않는다() {
        assertThat(PhotoUploadResourceProcess.successfulMedian(List.of(
                Map.of("success", false, "serviceMs", 1.0),
                Map.of("success", true, "serviceMs", 100.0),
                Map.of("success", true, "serviceMs", 200.0)))).isEqualTo(150.0);
        assertThat(PhotoUploadResourceProcess.successfulMedian(List.of(
                Map.of("success", false, "serviceMs", 1.0)))).isNull();
    }

    @Order(2)
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"standard-1-1", "standard-1-3", "standard-1-5", "large-1-1", "large-1-3", "large-1-5", "standard-10-1", "large-10-1"})
    @EnabledIfEnvironmentVariable(named = "RUN_UPLOAD_RESOURCE_BASELINE", matches = "true")
    void 현재_코드의_자원사용_기준값을_탐색한다(String condition) throws Exception {
        Path fixtures = directory.resolve("fixtures");
        if (!Files.isRegularFile(fixtures.resolve("fixtures.json"))) run("fixtures", fixtures, "fixtures", 256);
        JsonNode result = run(condition, fixtures, condition, 512);
        assertThat(result.path("summary").path("failedRequests").asInt()).withFailMessage("실패도 측정 결과에 보존했습니다: %s", REPORT.resolve(condition+".json")).isZero();
        assertThat(result.path("summary").path("uploadPeak").asInt()).isBetween(1,3);
        assertThat(result.path("summary").path("observedHeapPeakBytes").asLong()).isPositive();
    }

    private static JsonNode run(String mode, Path fixtures, String name, int heapMiB) throws Exception {
        Files.createDirectories(REPORT);
        Path work = Files.createDirectory(directory.resolve("process-"+name));
        Path result = work.resolve("result.json");
        Path log = REPORT.resolve(name+".log");
        Path gcLog = REPORT.resolve(name+"-gc.log");
        Path args = work.resolve("java.args");
        String classpath = System.getProperty("r2.recovery.test.classpath");
        assertThat(classpath).as("Gradle test 작업으로 실행해야 합니다.").isNotBlank();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        // GC 로그 파일명은 자식 작업 폴더 기준으로 지정해 Windows 드라이브의 ':' 해석을 피한다.
        List<String> command = List.of("-Xms"+heapMiB+"m", "-Xmx"+heapMiB+"m", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=true", "-Djava.io.tmpdir="+work, "-Djava.util.concurrent.ForkJoinPool.common.parallelism=8",
                "-Xlog:gc:file=gc.log:time,uptime,level,tags", "-cp", classpath, PhotoUploadResourceProcess.class.getName(), mode, fixtures.toString(), result.toString());
        Files.writeString(args,command.stream().map(PhotoUploadResourceBenchmarkTest::quote).collect(Collectors.joining(System.lineSeparator())),StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(java.toString(),"@"+args).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().clear();
        for (String key : List.of("SystemRoot","WINDIR","PATH","TEMP","TMP","LANG","LC_ALL")) {
            String value=System.getenv(key); if(value!=null)builder.environment().put(key,value);
        }
        Process child=builder.start(); boolean finished;
        try { finished=child.waitFor(180,TimeUnit.SECONDS); }
        finally { if(child.isAlive()){child.destroyForcibly(); assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();} }
        if(Files.exists(work.resolve("gc.log")))Files.copy(work.resolve("gc.log"),gcLog,StandardCopyOption.REPLACE_EXISTING);
        String output=Files.readString(log,StandardCharsets.UTF_8);
        if(!finished || child.exitValue()!=0 || (!mode.endsWith("fixture") && !mode.equals("fixtures") && !Files.isRegularFile(result))) {
            JSON.writerWithDefaultPrettyPrinter().writeValue(REPORT.resolve(name+"-process-failure.json").toFile(),Map.of(
                    "mode",mode,"finished",finished,"exitCode",child.exitValue(),"oomInLog",output.contains("OutOfMemoryError"),"log",log.toString()));
        }
        assertThat(finished).withFailMessage("측정 자식 JVM 시간 초과. 로그: %s",log).isTrue();
        assertThat(child.exitValue()).withFailMessage("측정 자식 JVM 실패. 로그: %s%n%s",log,output.substring(Math.max(0,output.length()-6000))).isZero();
        if(mode.equals("fixtures") || mode.equals("smoke-fixture")) {
            Files.copy(fixtures.resolve("fixtures.json"),REPORT.resolve(name+".json"),StandardCopyOption.REPLACE_EXISTING);
            return JSON.readTree(fixtures.resolve("fixtures.json").toFile());
        }
        var resultJson=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(result.toFile());
        resultJson.put("runId",RUN_ID);
        var hashes=resultJson.putObject("productionSourceSha256");
        for(String source:List.of("PhotoService.java","GcsService.java"))hashes.put(source,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(Path.of("src/main/java/com/yeogidot/yeogidot/service",source)))));
        JSON.writerWithDefaultPrettyPrinter().writeValue(REPORT.resolve(name+".json").toFile(),resultJson);
        writeSummary();
        System.out.println("UPLOAD_RESOURCE_RESULT "+name+" "+resultJson.path("summary"));
        return resultJson;
    }

    private static void writeSummary() throws Exception {
        List<String> rows=new ArrayList<>(List.of("condition,request_samples,successes,failures,all_outcomes_service_median_ms,all_outcomes_service_min_ms,all_outcomes_service_max_ms,observed_heap_peak_mib,gc_collection_ms,gc_count,validation_peak,upload_peak,post_validation_pending_peak,successful_service_median_ms"));
        try(var files=Files.list(REPORT)) {
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()) {
                JsonNode r=JSON.readTree(file.toFile()),s=r.path("summary");if(s.isMissingNode())continue;
                rows.add(String.format(Locale.ROOT,"%s,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d,%s",r.path("scenario").asText(),s.path("requestSamples").asInt(),s.path("successfulRequests").asInt(),s.path("failedRequests").asInt(),
                        s.path("serviceMedianMs").asDouble(),s.path("serviceMinMs").asDouble(),s.path("serviceMaxMs").asDouble(),s.path("observedHeapPeakBytes").asLong()/1048576.0,
                        s.path("gcCollectionTimeMs").asLong(),s.path("gcCollections").asLong(),s.path("validationPeak").asInt(),s.path("uploadPeak").asInt(),s.path("postValidationPendingPeak").asInt(),
                        s.path("successfulServiceMedianMs").isNull() ? "" : String.format(Locale.ROOT,"%.3f",s.path("successfulServiceMedianMs").asDouble())));
            }
        }
        Files.write(REPORT.resolve("summary.csv"),rows,StandardCharsets.UTF_8);
    }
    private static String quote(String arg){return "\""+arg.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
}

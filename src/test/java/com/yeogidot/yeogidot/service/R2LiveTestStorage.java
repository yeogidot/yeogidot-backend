package com.yeogidot.yeogidot.service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** No default AWS credentials or production configuration. Only three generated fixture keys. */
final class R2LiveTestStorage implements AutoCloseable {
    static final String BUCKET = "yeogidot-r2-delete-test";
    static final String URL_BASE = "https://r2-test.invalid"; // Identifier only; never fetched over HTTP.
    private final Guard guard = new Guard();
    private final S3Client client;
    private final AtomicInteger deleteInvocations = new AtomicInteger();

    R2LiveTestStorage(Map<String, String> environment) {
        URI endpoint = validate(environment);
        client = S3Client.builder().endpointOverride(endpoint).region(Region.of("auto"))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        required(environment, "R2_LIVE_ACCESS_KEY_ID"),
                        required(environment, "R2_LIVE_SECRET_ACCESS_KEY"))))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))
                        .addExecutionInterceptor(new ExecutionInterceptor() {
                            @Override public void beforeExecution(Context.BeforeExecution context,
                                                                  ExecutionAttributes attributes) {
                                guard.check(context.request());
                                if (context.request() instanceof DeleteObjectRequest) {
                                    deleteInvocations.incrementAndGet();
                                }
                            }
                        })).build();
    }

    static URI validate(Map<String, String> env) {
        if (!"true".equals(env.get("RUN_R2_LIVE_TEST"))
                || !"true".equals(env.get("RUN_R2_MYSQL_TEST"))) {
            throw new IllegalArgumentException("Both live R2 and MySQL opt-ins are required");
        }
        if (!BUCKET.equals(env.get("R2_LIVE_BUCKET"))) {
            throw new IllegalArgumentException("Only the dedicated test bucket is allowed");
        }
        String endpoint = required(env, "R2_LIVE_ENDPOINT");
        if (!endpoint.matches("https://[a-f0-9]{32}\\.r2\\.cloudflarestorage\\.com/?")) {
            throw new IllegalArgumentException("Use the HTTPS account S3 endpoint without a bucket/path");
        }
        required(env, "R2_LIVE_ACCESS_KEY_ID");
        required(env, "R2_LIVE_SECRET_ACCESS_KEY");
        return URI.create(endpoint);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    String uploadFixture() {
        String key = guard.register(); // Register before upload so ambiguous upload failures can be cleaned up.
        System.out.println("R2 LIVE fixture key: " + key);
        client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(key)
                .contentType("text/plain").build(), RequestBody.fromString("R2 deletion test fixture"));
        return key;
    }

    boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
            return true;
        } catch (S3Exception failure) {
            // 403 and all other failures must fail verification, not masquerade as absence.
            if (failure.statusCode() == 404) return false;
            throw failure;
        }
    }

    S3Client client() { return client; }
    int deleteInvocations() { return deleteInvocations.get(); }
    void cleanup() {
        RuntimeException error = null;
        for (String key : Set.copyOf(guard.keys)) {
            try {
                client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build());
                if (exists(key)) throw new IllegalStateException("Fixture still exists after cleanup");
                guard.keys.remove(key);
            } catch (RuntimeException failure) {
                System.err.println("R2 LIVE cleanup failed; check test fixture key: " + key);
                if (error == null) error = new IllegalStateException("Test fixture cleanup incomplete");
                error.addSuppressed(failure);
            }
        }
        if (error != null) throw error;
    }

    @Override public void close() { client.close(); }

    static final class Guard {
        private final String prefix = "codex-r2-deletion-test/" + UUID.randomUUID() + "/";
        private final Set<String> keys = ConcurrentHashMap.newKeySet();
        private int registered;

        synchronized String register() {
            if (registered >= 3) throw new IllegalStateException("At most three live fixtures per run");
            String key = prefix + UUID.randomUUID() + ".txt";
            keys.add(key);
            registered++;
            return key;
        }

        void check(SdkRequest request) {
            String bucket;
            String key;
            if (request instanceof PutObjectRequest put) { bucket = put.bucket(); key = put.key(); }
            else if (request instanceof HeadObjectRequest head) { bucket = head.bucket(); key = head.key(); }
            else if (request instanceof DeleteObjectRequest delete) { bucket = delete.bucket(); key = delete.key(); }
            else throw new IllegalArgumentException("Only fixture put/head/delete operations are allowed");
            if (!BUCKET.equals(bucket) || key == null || !key.startsWith(prefix) || !keys.contains(key)) {
                throw new IllegalArgumentException("Request outside this run's registered test fixtures");
            }
        }
    }
}

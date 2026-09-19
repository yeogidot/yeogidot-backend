package com.yeogidot.yeogidot.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.*;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** No client construction, credentials or network needed for these guards. */
class R2LiveTestSafetyTest {
    private Map<String, String> valid() {
        return new HashMap<>(Map.of("RUN_R2_LIVE_TEST", "true", "RUN_R2_MYSQL_TEST", "true",
                "R2_LIVE_BUCKET", R2LiveTestStorage.BUCKET,
                "R2_LIVE_ENDPOINT", "https://" + "a".repeat(32) + ".r2.cloudflarestorage.com",
                "R2_LIVE_ACCESS_KEY_ID", "fake", "R2_LIVE_SECRET_ACCESS_KEY", "fake"));
    }

    @Test void 허용된_설정만_통과한다() {
        assertThat(R2LiveTestStorage.validate(valid()).getScheme()).isEqualTo("https");
        for (String name : valid().keySet()) {
            var env = valid(); env.remove(name);
            assertThatThrownBy(() -> R2LiveTestStorage.validate(env)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void 운영_버킷을_거부한다() {
        var env = valid(); env.put("R2_LIVE_BUCKET", "yeogidot-storage");
        assertThatThrownBy(() -> R2LiveTestStorage.validate(env)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 다른_호스트와_경로를_거부한다() {
        for (String endpoint : new String[]{"http://localhost", "https://example.com",
                valid().get("R2_LIVE_ENDPOINT") + "/bucket", valid().get("R2_LIVE_ENDPOINT") + "?x=1"}) {
            var env = valid(); env.put("R2_LIVE_ENDPOINT", endpoint);
            assertThatThrownBy(() -> R2LiveTestStorage.validate(env)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void 이번_실행에서_등록한_키만_허용한다() {
        var guard = new R2LiveTestStorage.Guard();
        String key = guard.register();
        guard.check(PutObjectRequest.builder().bucket(R2LiveTestStorage.BUCKET).key(key).build());
        guard.check(HeadObjectRequest.builder().bucket(R2LiveTestStorage.BUCKET).key(key).build());
        guard.check(DeleteObjectRequest.builder().bucket(R2LiveTestStorage.BUCKET).key(key).build());
        assertThatThrownBy(() -> guard.check(DeleteObjectRequest.builder().bucket("yeogidot-storage")
                .key(key).build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(DeleteObjectRequest.builder().bucket(R2LiveTestStorage.BUCKET)
                .key("existing-file.jpg").build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check(ListObjectsV2Request.builder().bucket(R2LiveTestStorage.BUCKET)
                .build())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void 세개를_초과한_업로드_등록을_거부한다() {
        var guard = new R2LiveTestStorage.Guard();
        guard.register(); guard.register(); guard.register();
        assertThatThrownBy(guard::register).isInstanceOf(IllegalStateException.class);
    }
}

package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Component
@Profile("prod")
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "photo.thumbnail.enabled", havingValue = "true", matchIfMissing = true)
public class MapPhotoThumbnailWorker {
    private static final int MAX_ATTEMPTS = 4;

    private final PhotoRepository photos;
    private final GcsService storage;
    private final PlatformTransactionManager transactionManager;

    @Bean(defaultCandidate = false)
    ThreadPoolTaskScheduler mapPhotoThumbnailScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("map-thumbnail-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${photo.thumbnail.poll-interval-ms:3000}", scheduler = "mapPhotoThumbnailScheduler")
    public void runOnce() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var pending = photos.findPendingMapThumbnails(now, MAX_ATTEMPTS, PageRequest.of(0, 1));
        if (pending.isEmpty()) return;

        Photo photo = pending.getFirst();
        int attempts = photo.getThumbnailAttempts();
        int attempt = attempts + 1;
        LocalDateTime leaseUntil = now.plusMinutes(1L << attempts);
        if (photos.claimMapThumbnail(photo.getId(), attempts, now, leaseUntil) != 1) return;

        try {
            byte[] thumbnail = storage.createMapThumbnail(photo.getFilePath());
            String url = photo.mapThumbnailObjectUrl();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // DB 변경을 먼저 실행해 같은 사진의 삭제와 R2 저장 순서를 맞춘다.
                // 생성 작업은 DB 잠금 밖에서 끝내고, 작은 썸네일의 업로드만 기다린다.
                if (photos.publishMapThumbnail(photo.getId(), attempt, url) == 1) {
                    storage.uploadMapThumbnail(url, thumbnail);
                }
            });
        } catch (IOException | RuntimeException exception) {
            log.warn("지도 썸네일 생성 실패: photoId={}, attempt={}, error={}",
                    photo.getId(), attempt, exception.getClass().getSimpleName());
        }
    }
}

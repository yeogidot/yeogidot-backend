package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * [사진] 엔티티
 * - 업로드된 사진의 경로와 메타데이터(위치, 시간)를 저장합니다.
 * - 동선 정렬을 위해 (day_id, taken_at) 복합 인덱스가 걸려 있습니다.
 */
@Entity
@Getter
@Table(name = "photo", indexes = {
        @Index(name = "idx_photo_day_time", columnList = "day_id, taken_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Photo extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_id", nullable = false)
    private TravelDay travelDay;

    @Column(nullable = false, length = 2048)
    private String filePath; // 서버에 저장된 파일 경로 (또는 S3 URL)

    // 위도 (정밀도 보장을 위해 BigDecimal 사용)
    @Column(precision = 10, scale = 8, nullable = false)
    private BigDecimal latitude;

    // 경도
    @Column(precision = 11, scale = 8, nullable = false)
    private BigDecimal longitude;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt; // 사진 촬영 시간 (EXIF)

    // 사진 삭제 시 달린 코멘트도 함께 삭제
    @OneToMany(mappedBy = "photo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Cment> comments = new ArrayList<>();

    @Builder
    public Photo(TravelDay travelDay, String filePath, BigDecimal latitude, BigDecimal longitude, LocalDateTime takenAt) {
        this.travelDay = travelDay;
        this.filePath = filePath;
        this.latitude = latitude;
        this.longitude = longitude;
        this.takenAt = takenAt;
    }
}
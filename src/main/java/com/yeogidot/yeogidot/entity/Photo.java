package com.yeogidot.yeogidot.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * [사진] 엔티티
 * - 사진을 먼저 업로드하고 나중에 여행에 추가할 수 있도록 설계
 * - latitude, longitude는 EXIF가 없는 경우 nullable
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "photo", indexes = {
        @Index(name = "idx_photo_day_time", columnList = "day_id, taken_at")
})
public class Photo extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_id")
    private Long id;

    @JsonIgnore  // JSON 직렬화 시 제외
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;  // 사진을 업로드한 사용자

    @JsonIgnore  // JSON 직렬화 시 제외
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_id")
    private TravelDay travelDay;

    @Column(name = "file_path", length = 2048, nullable = false)
    private String filePath;

    @Column(name = "original_name")
    private String originalName;

    // 위도 (EXIF가 없으면 null)
    @Column(precision = 10, scale = 8)
    private BigDecimal latitude;

    // 경도 (EXIF가 없으면 null)
    @Column(precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    @JsonIgnore  // JSON 직렬화 시 제외 (Lazy Loading 에러 방지)
    @OneToMany(mappedBy = "photo", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdDate ASC")
    @Builder.Default
    private List<Cment> comments = new ArrayList<>();

    // URL getter (filePath를 url로 사용)
    public String getUrl() {
        return this.filePath;
    }

    // TravelDay 설정 편의 메서드
    public void setTravelDay(TravelDay travelDay) {
        this.travelDay = travelDay;
    }

    // 촬영 시간 수정 메서드
    public void updateTakenAt(LocalDateTime takenAt) {
        this.takenAt = takenAt;
    }
    
    // 위치 정보 수정 메서드
    public void updateLocation(BigDecimal latitude, BigDecimal longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}

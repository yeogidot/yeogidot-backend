package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "photo")
public class Photo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "photo_id")
    private Long photoId;


    @Column(name = "day_id")
    private Integer dayId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_id", insertable = false, updatable = false)
    private TravelDay travelDay;

    @Column(name = "file_path", nullable = false, length = 2048)
    private String url;

    @Column(name = "original_name")
    private String originalName;

    // 좌표값 (친구 코드대로 BigDecimal 사용)
    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    @Column(name = "taken_at", nullable = false)
    private LocalDateTime takenAt;

    @CreationTimestamp
    @Column(name = "photo_created", nullable = false, updatable = false)
    private LocalDateTime photoCreated;
}
package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate; // 날짜 타입

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "travel")
public class Travel {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "travel_id")
    private Long travelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String title;

    // [★ 추가됨] 여행 지역 (예: 부산광역시)
    @Column(name = "trv_region")
    private String trvRegion;

    // [★ 추가됨] 시작일
    @Column(name = "start_date")
    private LocalDate startDate;

    // [★ 추가됨] 종료일
    @Column(name = "end_date")
    private LocalDate endDate;

    // [★ 추가됨] 대표 사진 ID (다른 팀원이 선택한 사진의 ID)
    @Column(name = "representative_photo_id")
    private Long representativePhotoId;
}
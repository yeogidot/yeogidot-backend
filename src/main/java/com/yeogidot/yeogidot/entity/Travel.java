package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
//여행 엔티티

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "travel")
public class Travel extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "travel_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "trv_region")
    private String trvRegion;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "representative_photo_id")
    private Long representativePhotoId;

    @Column(name = "share_url")
    private String shareUrl;

    @OneToMany(mappedBy = "travel", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("date ASC")
    @Builder.Default
    private Set<TravelDay> travelDays = new LinkedHashSet<>();

    public void addDay(TravelDay day) {
        this.travelDays.add(day);
        day.setTravel(this);
    }

    // 대표 사진 수정 메서드
    public void updateRepresentativePhoto(Long photoId) {
        this.representativePhotoId = photoId;
    }
    
    // 여행 제목 수정 메서드
    public void updateTitle(String title) {
        this.title = title;
    }

    // 여행 날짜 업데이트 메서드
    public void updateDates(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // 공유 URL 업데이트 메서드
    public void updateShareUrl(String shareUrl) {
        this.shareUrl = shareUrl;
    }
    
    // 여행 지역 업데이트 메서드
    public void updateTrvRegion(String trvRegion) {
        this.trvRegion = trvRegion;
    }
}
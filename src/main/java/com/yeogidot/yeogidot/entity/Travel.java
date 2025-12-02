package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * [여행] 엔티티
 * - 서비스의 가장 상위 개념입니다.
 * - User(사용자)와 N:1 관계를 가집니다.
 */
@Entity
@Getter
@Table(name = "travel")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Travel extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "travel_id")
    private Long id;

    // 작성자 (User 엔티티와 연관관계)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "trv_region")
    private String region; // 여행 대표 지역

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(name = "representative_photo_id")
    private Long representativePhotoId;

    private String shareUrl; // 공유용 URL

    /**
     * CascadeType.ALL, orphanRemoval = true
     * - 여행(Travel)이 삭제되면, 연결된 일차(TravelDay)들도 모두 자동으로 삭제됩니다.
     */
    @OneToMany(mappedBy = "travel", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TravelDay> days = new ArrayList<>();

    @Builder
    public Travel(User user, String title, LocalDate startDate, LocalDate endDate) {
        this.user = user;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // 연관관계 편의 메서드 (객체 양방향 연결용)
    public void addDay(TravelDay day) {
        this.days.add(day);
        day.setTravel(this);
    }
}
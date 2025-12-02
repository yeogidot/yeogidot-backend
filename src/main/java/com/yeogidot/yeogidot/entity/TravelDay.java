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
 * [여행 일차] 엔티티
 * - 여행의 하루하루(1일차, 2일차...)를 나타냅니다.
 * - Travel(부모) -> TravelDay(중간) -> Photo/Log(자식) 구조입니다.
 */
@Entity
@Getter
@Table(name = "travel_day")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelDay extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "day_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "travel_id", nullable = false)
    private Travel travel;

    @Column(nullable = false)
    private Integer dayNumber; // 예: 1, 2, 3 ...

    @Column(name = "day_region")
    private String dayRegion; // 그날 방문한 대표 지역

    @Column(nullable = false)
    private LocalDate date;   // 실제 날짜 (예: 2024-05-01)

    // 일차(Day) 삭제 시 해당 날짜의 사진들도 모두 삭제
    @OneToMany(mappedBy = "travelDay", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Photo> photos = new ArrayList<>();

    // 일차(Day) 삭제 시 해당 날짜의 일기도 삭제
    @OneToOne(mappedBy = "travelDay", cascade = CascadeType.ALL, orphanRemoval = true)
    private TravelLog travelLog;

    @Builder
    public TravelDay(Integer dayNumber, LocalDate date, String dayRegion) {
        this.dayNumber = dayNumber;
        this.date = date;
        this.dayRegion = dayRegion;
    }

    // 연관관계 설정용 Setter
    public void setTravel(Travel travel) {
        this.travel = travel;
    }
}
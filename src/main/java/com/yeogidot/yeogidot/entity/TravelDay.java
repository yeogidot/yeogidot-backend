package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

//여행 일차 엔티티

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "travel_day")
public class TravelDay extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "day_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "travel_id")
    private Travel travel;

    @Column(nullable = false)
    private Integer dayNumber; // 일차 정보

    private String dayRegion; // 해당 일차의 주요 지역

    @Column(nullable = false)
    private LocalDate date; // 해당 일차의 날짜

    // 일차에 속한 로그
    @OneToMany(mappedBy = "travelDay", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TravelLog> travelLogs = new ArrayList<>();

    // 일차에 속한 사진 (orphanRemoval=false: TravelDay 삭제 시 사진은 유지, travelDay만 null로)
    @OneToMany(mappedBy = "travelDay", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @Builder.Default
    private List<Photo> photos = new ArrayList<>();

    public void setTravel(Travel travel) {
        this.travel = travel;
    }

    // dayNumber 업데이트 메서드
    public void updateDayNumber(Integer dayNumber) {
        this.dayNumber = dayNumber;
    }

    // dayRegion 업데이트 메서드
    public void updateDayRegion(String dayRegion) {
        this.dayRegion = dayRegion;
    }
}
package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;

// 여행 일기 엔티티

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "travel_log")
public class TravelLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_id", nullable = false)
    private TravelDay travelDay;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    public void updateContent(String content) {
        this.content = content;
    }

    public void setTravelDay(TravelDay travelDay) {
        this.travelDay = travelDay;
    }
}
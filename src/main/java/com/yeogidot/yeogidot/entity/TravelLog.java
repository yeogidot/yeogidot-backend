package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [여행 일기] 엔티티
 * - 특정 일차(TravelDay)에 작성된 줄글 일기입니다.
 * - TravelDay와 1:1 관계입니다.
 */
@Entity
@Getter
@Table(name = "travel_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelLog extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_id", nullable = false)
    private TravelDay travelDay;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;
}
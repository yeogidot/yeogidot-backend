package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "travel_day")
public class TravelDay {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "day_id")
    private Long dayId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "travel_id")
    private Travel travel;
}
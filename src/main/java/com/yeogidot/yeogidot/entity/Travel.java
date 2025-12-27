package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "travel")
public class Travel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "travel_id")
    private Long travelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String title;
}
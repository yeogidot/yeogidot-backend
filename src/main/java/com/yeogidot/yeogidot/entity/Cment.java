package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 사진 코멘트
// 개별 사진에 달리는 짧은 코멘트

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cment")
public class Cment extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id")
    private Photo photo;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Builder
    public Cment(Photo photo, String content) {
        this.photo = photo;
        this.content = content;
    }

    public void updateContent(String content) {
        this.content = content;
    }
}
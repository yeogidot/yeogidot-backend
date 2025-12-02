package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [사진 코멘트] 엔티티
 * - 개별 사진에 달리는 짧은 코멘트입니다.
 */
@Entity
@Getter
@Table(name = "cment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cment extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private Photo photo;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;
}
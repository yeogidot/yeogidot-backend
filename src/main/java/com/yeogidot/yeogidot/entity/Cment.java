package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * 사진 댓글 엔티티
 * - 개별 사진에 달리는 짧은 댓글
 * - 여러 사용자가 한 사진에 여러 댓글 작성 가능
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "cment", indexes = {
        @Index(name = "idx_cment_photo", columnList = "photo_id"),
        @Index(name = "idx_cment_writer", columnList = "writer_id")
})
public class Cment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private Photo photo;

    // 댓글 작성자 추가
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "writer_id", nullable = false)
    private User writer;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Builder
    public Cment(Photo photo, User writer, String content) {
        this.photo = photo;
        this.writer = writer;
        this.content = content;
    }

    /**
     * 댓글 내용 수정
     */
    public void updateContent(String content) {
        this.content = content;
    }

    /**
     * 작성자 확인
     */
    public boolean isWrittenBy(Long userId) {
        return this.writer.getId().equals(userId);
    }
}
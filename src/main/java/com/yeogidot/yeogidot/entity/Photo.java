package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "photos") // DB 테이블 이름
@Getter @Setter
@NoArgsConstructor
public class Photo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long photoId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "uploaded_date")
    private String uploadedDate;

    // 편의를 위한 생성자
    public Photo(String title, String description, String imageUrl, String uploadedDate) {
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
        this.uploadedDate = uploadedDate;
    }
}

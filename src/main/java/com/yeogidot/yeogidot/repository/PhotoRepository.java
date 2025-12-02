package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [사진 Repository]
 * - Photo 엔티티의 DB 접근을 담당합니다.
 * - JpaRepository<Entity타입, PK타입>을 상속받아 기본적인 CRUD 메서드를 자동으로 제공받습니다.
 */
public interface PhotoRepository extends JpaRepository<Photo, Long> {
}
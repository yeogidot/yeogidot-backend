package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo; // Entity 패키지에 Photo 클래스가 있어야 함
import org.springframework.data.jpa.repository.JpaRepository;

public interface PhotoRepository extends JpaRepository<Photo, Long> {
}
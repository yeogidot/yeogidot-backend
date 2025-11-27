package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

// <Entity 타입, PK 타입>
public interface PhotoRepository extends JpaRepository<Photo, Long> {
}

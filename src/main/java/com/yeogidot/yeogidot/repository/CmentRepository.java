package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Cment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CmentRepository extends JpaRepository<Cment, Long> {
    Optional<Cment> findByPhotoId(Long photoId);
}
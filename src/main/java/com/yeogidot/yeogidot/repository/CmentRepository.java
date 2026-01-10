package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Cment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CmentRepository extends JpaRepository<Cment, Long> {
}
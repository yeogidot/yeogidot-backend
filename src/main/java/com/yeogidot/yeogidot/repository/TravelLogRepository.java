package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.TravelLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TravelLogRepository extends JpaRepository<TravelLog, Long> {
}
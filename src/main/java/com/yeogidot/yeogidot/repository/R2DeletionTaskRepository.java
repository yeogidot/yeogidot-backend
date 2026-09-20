package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.R2DeletionTask;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface R2DeletionTaskRepository extends JpaRepository<R2DeletionTask, Long> {
    @Query("select t.id from R2DeletionTask t where t.status in :states " +
            "and t.availableAt <= :now order by t.availableAt, t.id")
    List<Long> findReadyIds(@Param("states") Collection<R2DeletionTask.Status> states,
                           @Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from R2DeletionTask t where t.id = :id")
    Optional<R2DeletionTask> findLockedById(@Param("id") Long id);
}

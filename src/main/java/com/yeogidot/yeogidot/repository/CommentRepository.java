package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {
    Optional<Comment> findByPhotoId(Long photoId);
}
package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    Optional<Comment> findByPhotoId(Long photoId);

    // 회원탈퇴 시 해당 유저가 작성한 댓글 삭제 (다른 사람 사진에 단 댓글 포함)
    @Modifying
    @Transactional
    void deleteByWriterId(Long writerId);
}

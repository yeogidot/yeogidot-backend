package com.yeogidot.yeogidot.repository;

import com.yeogidot.yeogidot.entity.Comment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = {"writer", "photo.user"})
    Optional<Comment> findByIdAndPhotoId(Long id, Long photoId);

    // 구버전 API는 두 건까지만 조회해 대상이 하나인지 확인한다.
    @EntityGraph(attributePaths = {"writer", "photo.user"})
    List<Comment> findTop2ByPhotoIdOrderByIdAsc(Long photoId);

    // 회원탈퇴 시 해당 유저가 작성한 댓글 삭제 (다른 사람 사진에 단 댓글 포함)
    @Modifying
    @Transactional
    void deleteByWriterId(Long writerId);
}

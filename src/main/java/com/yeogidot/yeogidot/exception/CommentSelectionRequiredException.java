package com.yeogidot.yeogidot.exception;

/** 사진 ID만으로는 여러 댓글 중 처리할 대상을 선택할 수 없다. */
public class CommentSelectionRequiredException extends RuntimeException {
    public CommentSelectionRequiredException() {
        super("댓글이 여러 개 있습니다. 댓글 ID를 지정하는 API를 사용해주세요.");
    }
}

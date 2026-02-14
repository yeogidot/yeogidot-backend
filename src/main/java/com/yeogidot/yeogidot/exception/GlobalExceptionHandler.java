package com.yeogidot.yeogidot.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.util.Map;

/**
 * 전역 예외 처리기
 * Spring Security 예외를 포함한 모든 예외를 일관된 형식으로 처리
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 인증 실패 (401 Unauthorized)
     * - JWT 토큰 없음
     * - JWT 토큰 만료
     * - JWT 토큰 형식 오류
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> handleAuthenticationException(AuthenticationException e) {
        log.error("인증 실패: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "status", 401,
                "error", "UNAUTHORIZED",
                "message", "인증이 필요합니다. JWT 토큰을 확인해주세요.",
                "detail", e.getMessage()
        ));
    }

    /**
     * 권한 없음 (403 Forbidden)
     * - 로그인은 했지만 해당 리소스에 접근할 권한이 없음
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDeniedException(AccessDeniedException e) {
        log.error("권한 없음: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", 403,
                "error", "FORBIDDEN",
                "message", "해당 리소스에 접근할 권한이 없습니다.",
                "detail", e.getMessage()
        ));
    }

    /**
     * 보안 예외 (403 Forbidden)
     * - 본인의 리소스가 아님
     * - 삭제/수정 권한 없음
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> handleSecurityException(SecurityException e) {
        log.error("보안 예외: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "status", 403,
                "error", "SECURITY_VIOLATION",
                "message", e.getMessage()
        ));
    }

    /**
     * 필수 파일 누락 (400 Bad Request)
     * - multipart/form-data 요청에서 필수 파일(files)이 없음
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<?> handleMissingServletRequestPartException(MissingServletRequestPartException e) {
        log.error("필수 파일 누락: {}", e.getMessage());
        
        String partName = e.getRequestPartName();
        String message = "필수 파일이 누락되었습니다.";
        
        if ("files".equals(partName)) {
            message = "업로드할 사진 파일(files)이 필요합니다.";
        } else if ("metadata".equals(partName)) {
            message = "사진 메타데이터(metadata)가 필요합니다.";
        }
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "MISSING_REQUIRED_PART",
                "message", message,
                "detail", String.format("필수 파라미터 '%s'가 누락되었습니다.", partName)
        ));
    }

    /**
     * 필수 파라미터 누락 (400 Bad Request)
     * - @RequestParam으로 지정된 필수 파라미터가 없음
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.error("필수 파라미터 누락: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "MISSING_PARAMETER",
                "message", String.format("필수 파라미터 '%s'가 누락되었습니다.", e.getParameterName()),
                "detail", e.getMessage()
        ));
    }

    /**
     * 요청 바디 형식 오류 (400 Bad Request)
     * - JSON 파싱 실패
     * - 잘못된 데이터 형식
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.error("요청 바디 형식 오류: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "INVALID_REQUEST_BODY",
                "message", "요청 데이터 형식이 올바르지 않습니다.",
                "detail", "JSON 형식을 확인해주세요."
        ));
    }

    /**
     * 파라미터 타입 불일치 (400 Bad Request)
     * - 숫자를 기대했는데 문자열이 들어옴
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.error("파라미터 타입 불일치: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "INVALID_PARAMETER_TYPE",
                "message", String.format("파라미터 '%s'의 값이 올바르지 않습니다.", e.getName()),
                "detail", String.format("'%s' 타입이 필요합니다.", e.getRequiredType().getSimpleName())
        ));
    }

    /**
     * Validation 실패 (400 Bad Request)
     * - @Valid 검증 실패
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("검증 실패: {}", e.getMessage());
        
        String firstError = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("입력값이 올바르지 않습니다.");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "VALIDATION_FAILED",
                "message", firstError,
                "detail", "입력값을 확인해주세요."
        ));
    }

    /**
     * 잘못된 인자 (400 Bad Request)
     * - 필수 파라미터 누락
     * - 잘못된 데이터 형식
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", "BAD_REQUEST",
                "message", e.getMessage()
        ));
    }

    /**
     * 리소스를 찾을 수 없음 (404 Not Found)
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalStateException(IllegalStateException e) {
        log.error("상태 오류: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "status", 404,
                "error", "NOT_FOUND",
                "message", e.getMessage()
        ));
    }

    /**
     * 파일 크기 초과 (413 Payload Too Large)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.error("파일 크기 초과: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "status", 413,
                "error", "FILE_TOO_LARGE",
                "message", "업로드 파일 크기가 제한을 초과했습니다.",
                "detail", "최대 파일 크기는 10MB입니다."
        ));
    }

    /**
     * IO 예외 (500 Internal Server Error)
     * - 파일 업로드/다운로드 실패
     * - GCS 연결 실패
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> handleIOException(IOException e) {
        log.error("IO 오류: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", "IO_ERROR",
                "message", "파일 처리 중 오류가 발생했습니다.",
                "detail", e.getMessage()
        ));
    }

    /**
     * 런타임 예외 (500 Internal Server Error)
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntimeException(RuntimeException e) {
        log.error("런타임 예외: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", "RUNTIME_ERROR",
                "message", "서버 처리 중 오류가 발생했습니다.",
                "detail", e.getMessage()
        ));
    }

    /**
     * 그 외 모든 예외 (500 Internal Server Error)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("예상치 못한 예외: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", "INTERNAL_SERVER_ERROR",
                "message", "예상치 못한 오류가 발생했습니다.",
                "detail", e.getMessage()
        ));
    }
}

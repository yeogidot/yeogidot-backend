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
import java.util.HashMap;
import java.util.Map;

/**
 * 전역 예외 처리기
 * Spring Security 예외를 포함한 모든 예외를 일관된 형식으로 처리
 * 
 * 우선순위: 구체적 예외 → 일반적 예외 순서로 처리
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * ===== 인증/인가 예외 (Spring Security) =====
     */

    /**
     * 인증 실패 (401 Unauthorized)
     * - JWT 토큰 없음
     * - JWT 토큰 만료
     * - JWT 토큰 형식 오류
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException e) {
        log.error("인증 실패: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 401);
        response.put("error", "UNAUTHORIZED");
        response.put("message", "인증이 필요합니다. JWT 토큰을 확인해주세요.");
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * 권한 없음 (403 Forbidden)
     * - 로그인은 했지만 해당 리소스에 접근할 권한이 없음
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException e) {
        log.error("권한 없음: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 403);
        response.put("error", "FORBIDDEN");
        response.put("message", "해당 리소스에 접근할 권한이 없습니다.");
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * 보안 예외 (403 Forbidden)
     * - 본인의 리소스가 아님
     * - 삭제/수정 권한 없음
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurityException(SecurityException e) {
        log.error("보안 예외: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 403);
        response.put("error", "SECURITY_VIOLATION");
        response.put("message", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * ===== 요청 파라미터/바디 관련 예외 (400 Bad Request) =====
     */

    /**
     * 필수 파일 누락 (400 Bad Request)
     * - multipart/form-data 요청에서 필수 파일(files)이 없음
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestPartException(MissingServletRequestPartException e) {
        log.error("필수 파일 누락: {}", e.getMessage());
        
        String partName = e.getRequestPartName();
        String message = "필수 파일이 누락되었습니다.";
        
        if ("files".equals(partName)) {
            message = "업로드할 사진 파일(files)이 필요합니다.";
        } else if ("metadata".equals(partName)) {
            message = "사진 메타데이터(metadata)가 필요합니다.";
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "MISSING_REQUIRED_PART");
        response.put("message", message);
        response.put("detail", String.format("필수 파라미터 '%s'가 누락되었습니다.", partName));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 필수 파라미터 누락 (400 Bad Request)
     * - @RequestParam으로 지정된 필수 파라미터가 없음
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.error("필수 파라미터 누락: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "MISSING_PARAMETER");
        response.put("message", String.format("필수 파라미터 '%s'가 누락되었습니다.", e.getParameterName()));
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 요청 바디 형식 오류 (400 Bad Request)
     * - JSON 파싱 실패
     * - 잘못된 데이터 형식
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.error("요청 바디 형식 오류: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "INVALID_REQUEST_BODY");
        response.put("message", "요청 데이터 형식이 올바르지 않습니다.");
        response.put("detail", "JSON 형식을 확인해주세요.");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 파라미터 타입 불일치 (400 Bad Request)
     * - 숫자를 기대했는데 문자열이 들어옴
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.error("파라미터 타입 불일치: {}", e.getMessage());
        
        String requiredType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "INVALID_PARAMETER_TYPE");
        response.put("message", String.format("파라미터 '%s'의 값이 올바르지 않습니다.", e.getName()));
        response.put("detail", String.format("'%s' 타입이 필요합니다.", requiredType));
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Validation 실패 (400 Bad Request)
     * - @Valid 검증 실패
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("검증 실패: {}", e.getMessage());
        
        String firstError = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("입력값이 올바르지 않습니다.");
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "VALIDATION_FAILED");
        response.put("message", firstError);
        response.put("detail", "입력값을 확인해주세요.");
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 잘못된 인자 (400 Bad Request)
     * - 필수 파라미터 누락
     * - 잘못된 데이터 형식
     * - PhotoService에서 던지는 비즈니스 로직 예외
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("잘못된 요청: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 400);
        response.put("error", "BAD_REQUEST");
        response.put("message", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * ===== 리소스 관련 예외 (404 Not Found) =====
     */

    /**
     * 리소스를 찾을 수 없음 (404 Not Found)
     * - 사진 ID가 존재하지 않음
     * - 여행 ID가 존재하지 않음
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException e) {
        log.error("상태 오류: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 404);
        response.put("error", "NOT_FOUND");
        response.put("message", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * ===== 파일 업로드 관련 예외 =====
     */

    /**
     * 파일 크기 초과 (413 Payload Too Large)
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.error("파일 크기 초과: {}", e.getMessage());
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 413);
        response.put("error", "FILE_TOO_LARGE");
        response.put("message", "업로드 파일 크기가 제한을 초과했습니다.");
        response.put("detail", "최대 파일 크기는 10MB입니다.");
        
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(response);
    }

    /**
     * IO 예외 (500 Internal Server Error)
     * - 파일 업로드/다운로드 실패
     * - GCS 연결 실패
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException e) {
        log.error("IO 오류: {}", e.getMessage(), e);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 500);
        response.put("error", "IO_ERROR");
        response.put("message", "파일 처리 중 오류가 발생했습니다.");
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * ===== 일반 예외 (500 Internal Server Error) =====
     * 주의: 가장 마지막에 처리되어야 함
     */

    /**
     * 런타임 예외 (500 Internal Server Error)
     * - 예상치 못한 런타임 오류
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("런타임 예외: {}", e.getMessage(), e);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 500);
        response.put("error", "RUNTIME_ERROR");
        response.put("message", "서버 처리 중 오류가 발생했습니다.");
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 그 외 모든 예외 (500 Internal Server Error)
     * - 최후의 안전망
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("예상치 못한 예외: {}", e.getMessage(), e);
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", 500);
        response.put("error", "INTERNAL_SERVER_ERROR");
        response.put("message", "예상치 못한 오류가 발생했습니다.");
        response.put("detail", e.getMessage());
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}

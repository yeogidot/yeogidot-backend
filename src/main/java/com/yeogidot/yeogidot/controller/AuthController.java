package com.yeogidot.yeogidot.controller;
import org.springframework.http.HttpStatus;
// DTO
import com.yeogidot.yeogidot.dto.LoginRequest;
import com.yeogidot.yeogidot.dto.SignupRequest;

// Service
import com.yeogidot.yeogidot.service.AuthService;

// Spring Web & Lombok
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 자바 유틸 (Map 사용)
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body("회원가입 성공");
    }
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        // 1. 로그인 시키고 토큰 받아오기
        String token = authService.login(request);

        // 2. 토큰 포장해서 주기
        Map<String, String> response = Map.of(
                "token_type", "Bearer",
                "access_token", token
        );

        return ResponseEntity.ok(response);
    }
    // 로그아웃 (POST /api/auth/logout)
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        // 클라이언트가 토큰을 버리기
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }


}

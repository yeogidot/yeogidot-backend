package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.LoginRequest;
import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "인증", description = "회원가입, 로그인, 로그아웃 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 회원가입
     */
    @Operation(
            summary = "회원가입",
            description = "새로운 사용자 계정을 생성합니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "회원가입 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = "\"회원가입 성공\""
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "회원가입 실패 (이미 존재하는 이메일, 비밀번호 불일치, 개인정보 동의 미체크 등)"
            )
    })
    @PostMapping("/signup")
    public ResponseEntity<String> signup(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "회원가입 정보",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                        {
                          "email": "user@example.com",
                          "password": "password123",
                          "password_check": "password123",
                          "privacy_policy_agreed": true
                        }
                        """
                            )
                    )
            )
            @RequestBody SignupRequest request
    ) {
        authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body("회원가입 성공");
    }

    /**
     * 로그인
     */
    @Operation(
            summary = "로그인",
            description = "이메일과 비밀번호로 로그인하여 JWT 토큰을 발급받습니다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "token_type": "Bearer",
                      "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
                    }
                    """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "로그인 실패 (이메일 또는 비밀번호 불일치, 잘못된 요청 등)"
            )
    })
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "로그인 정보",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                        {
                          "email": "user@example.com",
                          "password": "password123"
                        }
                        """
                            )
                    )
            )
            @RequestBody LoginRequest request
    ) {
        // 1. 로그인 시키고 토큰 받아오기
        String token = authService.login(request);

        // 2. 토큰 포장해서 주기
        Map<String, String> response = Map.of(
                "token_type", "Bearer",
                "access_token", token
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 로그아웃
     */
    @Operation(
            summary = "로그아웃",
            description = "로그아웃을 수행합니다. 클라이언트에서 토큰을 삭제해야 완전한 로그아웃이 됩니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그아웃 성공",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                    {
                      "message": "로그아웃되었습니다."
                    }
                    """
                            )
                    )
            )
    })
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        // 클라이언트가 토큰을 버리면 그게 로그아웃
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }
}

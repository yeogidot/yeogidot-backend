package com.yeogidot.yeogidot.controller;

import com.yeogidot.yeogidot.dto.ChangePasswordRequest;
import com.yeogidot.yeogidot.dto.DeleteAccountRequest;
import com.yeogidot.yeogidot.dto.LoginRequest;
import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.exception.UnauthenticatedException;
import com.yeogidot.yeogidot.repository.UserRepository;
import com.yeogidot.yeogidot.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "인증", description = "회원가입, 로그인, 로그아웃, 비밀번호 변경, 회원탈퇴 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    @Operation(summary = "회원가입", description = "새로운 사용자 계정을 생성합니다")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "\"회원가입 성공\""))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (비밀번호 불일치, 개인정보 동의 미체크 등)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 400, "error": "BAD_REQUEST", "message": "비밀번호가 일치하지 않습니다."}
                                    """))),
            @ApiResponse(responseCode = "400", description = "이미 존재하는 이메일",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 400, "error": "BAD_REQUEST", "message": "이미 존재하는 이메일입니다."}
                                    """)))
    })
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.status(HttpStatus.CREATED).body("회원가입 성공");
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하여 JWT 토큰을 발급받습니다")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"token_type": "Bearer", "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."}
                                    """))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (이메일 또는 비밀번호 불일치)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 400, "error": "BAD_REQUEST", "message": "이메일 또는 비밀번호를 확인해주세요."}
                                    """)))
    })
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginRequest request) {
        String token = authService.login(request);
        return ResponseEntity.ok(Map.of(
                "token_type", "Bearer",
                "access_token", token
        ));
    }

    @Operation(summary = "로그아웃", description = "로그아웃을 수행합니다. 클라이언트에서 토큰을 삭제해야 완전한 로그아웃이 됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그아웃 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"message": "로그아웃되었습니다."}
                                    """)))
    })
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @Parameter(hidden = true)
            @RequestHeader(value = "Authorization", required = false) String bearerToken) {
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            authService.logout(bearerToken.substring(7));
        }
        return ResponseEntity.ok(Map.of("message", "로그아웃되었습니다."));
    }

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경합니다. JWT 인증이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "비밀번호 변경 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"message": "비밀번호가 변경되었습니다."}
                                    """))),
            @ApiResponse(responseCode = "400", description = "현재 비밀번호 불일치 또는 새 비밀번호 정책 위반",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 400, "error": "BAD_REQUEST", "message": "현재 비밀번호가 일치하지 않습니다."}
                                    """))),
            @ApiResponse(responseCode = "401", description = "인증 실패 (토큰 없음 또는 만료)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 401, "error": "UNAUTHORIZED", "message": "인증이 필요합니다."}
                                    """)))
    })
    @PatchMapping("/password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ChangePasswordRequest request) {
        User currentUser = getCurrentUser();
        authService.changePassword(currentUser.getId(), request);
        return ResponseEntity.ok(Map.of("message", "비밀번호가 변경되었습니다."));
    }

    @Operation(summary = "회원탈퇴", description = "비밀번호 확인 후 계정과 관련 데이터(사진, 여행)를 모두 삭제합니다. JWT 인증이 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원탈퇴 성공",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"message": "회원탈퇴가 완료되었습니다."}
                                    """))),
            @ApiResponse(responseCode = "400", description = "비밀번호 불일치",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 400, "error": "BAD_REQUEST", "message": "비밀번호가 일치하지 않습니다."}
                                    """))),
            @ApiResponse(responseCode = "401", description = "인증 실패 (토큰 없음 또는 만료)",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"status": 401, "error": "UNAUTHORIZED", "message": "인증이 필요합니다."}
                                    """)))
    })
    @DeleteMapping("/account")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @Parameter(hidden = true)
            @RequestHeader(value = "Authorization", required = false) String bearerToken,
            @RequestBody DeleteAccountRequest request) {
        User currentUser = getCurrentUser();

        String token = null;
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            token = bearerToken.substring(7);
        }

        authService.deleteAccount(currentUser.getId(), request, token);
        return ResponseEntity.ok(Map.of("message", "회원탈퇴가 완료되었습니다."));
    }

    /**
     * SecurityContext에서 현재 로그인 유저 조회
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthenticatedException("인증이 필요합니다.");
        }
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthenticatedException("인증이 필요합니다."));
    }
}

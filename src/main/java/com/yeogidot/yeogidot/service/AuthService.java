package com.yeogidot.yeogidot.service;

// DTO (요청 데이터)
import com.yeogidot.yeogidot.dto.LoginRequest;
import com.yeogidot.yeogidot.dto.SignupRequest;

// Entity (DB 테이블)
import com.yeogidot.yeogidot.entity.User;

// Repository (DB 도구)
import com.yeogidot.yeogidot.repository.UserRepository;

// Security (JWT 발급기, 비밀번호 암호화)
import com.yeogidot.yeogidot.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

// Spring 필수 어노테이션
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // 비밀번호 암호화 도구
    private final JwtTokenProvider jwtTokenProvider; // JWT 토큰 생성 도구
    @Transactional
    public void signup(SignupRequest request) {
        // 1. 약관 동의 체크
        if (!Boolean.TRUE.equals(request.getPrivacy_policy_agreed())) {
            throw new IllegalArgumentException("약관에 동의해야 합니다.");
        }

        // 2. 이메일 중복 체크
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }

        // 3. 비밀번호 일치 체크
        if (!request.getPassword().equals(request.getPassword_check())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 4. 유저 저장 (비밀번호 암호화 필수!)
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        userRepository.save(user);
    }
    // ★ 로그인 기능 추가
    @Transactional(readOnly = true)
    public String login(LoginRequest request) {
        // 1. 이메일로 사람 찾기
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("가입되지 않은 이메일입니다."));

        // 2. 비밀번호 맞는지 확인
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 틀렸습니다.");
        }

        // 3. 다 맞으면 발급기 버튼 눌러서 토큰 생성!
        return jwtTokenProvider.createToken(user.getId(), user.getEmail());
    }
}
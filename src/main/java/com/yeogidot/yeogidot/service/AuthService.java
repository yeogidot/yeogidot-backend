package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder; // 비밀번호 암호화 도구

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
}
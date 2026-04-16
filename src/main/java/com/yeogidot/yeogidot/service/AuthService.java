package com.yeogidot.yeogidot.service;

import com.yeogidot.yeogidot.dto.ChangePasswordRequest;
import com.yeogidot.yeogidot.dto.DeleteAccountRequest;
import com.yeogidot.yeogidot.dto.LoginRequest;
import com.yeogidot.yeogidot.dto.SignupRequest;
import com.yeogidot.yeogidot.exception.TooManyRequestsException;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.TimeUnit;

import com.yeogidot.yeogidot.entity.Photo;
import com.yeogidot.yeogidot.entity.Travel;
import com.yeogidot.yeogidot.entity.User;

import com.yeogidot.yeogidot.repository.CommentRepository;
import com.yeogidot.yeogidot.repository.PhotoRepository;
import com.yeogidot.yeogidot.repository.TravelRepository;
import com.yeogidot.yeogidot.repository.UserRepository;

import com.yeogidot.yeogidot.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final LoginAttemptService loginAttemptService;
    private final StringRedisTemplate redisTemplate;
    private final PhotoRepository photoRepository;
    private final TravelRepository travelRepository;
    private final CommentRepository commentRepository;
    private final GcsService gcsService;

    private static final String BLACKLIST_PREFIX = "blacklist:";

    @Transactional
    public void signup(SignupRequest request) {
        if (!Boolean.TRUE.equals(request.getPrivacy_policy_agreed())) {
            throw new IllegalArgumentException("약관에 동의해야 합니다.");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }
        if (!request.getPassword().matches("^(?=.*[a-zA-Z])(?=.*[0-9]).{8,}$")) {
            throw new IllegalArgumentException("비밀번호는 8자 이상, 영문과 숫자를 모두 포함해야 합니다.");
        }
        if (!request.getPassword().equals(request.getPassword_check())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        userRepository.save(user);
    }

    /**
     * 로그아웃 - 토큰을 Redis 블랙리스트에 등록 (TTL = 토큰 남은 만료 시간)
     */
    public void logout(String token) {
        long expiration = jwtTokenProvider.getExpiration(token);
        if (expiration > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + token,
                    "logout",
                    expiration,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    @Transactional
    public String login(LoginRequest request) {
        if (loginAttemptService.isBlocked(request.getEmail())) {
            throw new TooManyRequestsException("로그인 시도가 너무 많습니다. 5분 후 다시 시도해주세요.");
        }

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    loginAttemptService.loginFailed(request.getEmail());
                    return new IllegalArgumentException("이메일 또는 비밀번호를 확인해주세요.");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.loginFailed(request.getEmail());
            throw new IllegalArgumentException("이메일 또는 비밀번호를 확인해주세요.");
        }

        loginAttemptService.loginSucceeded(request.getEmail());
        return jwtTokenProvider.createToken(user.getId(), user.getEmail());
    }

    /**
     * 비밀번호 변경
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("현재 비밀번호가 일치하지 않습니다.");
        }

        if (!request.getNew_password().matches("^(?=.*[a-zA-Z])(?=.*[0-9]).{8,}$")) {
            throw new IllegalArgumentException("새 비밀번호는 8자 이상, 영문과 숫자를 모두 포함해야 합니다.");
        }

        if (request.getPassword().equals(request.getNew_password())) {
            throw new IllegalArgumentException("새 비밀번호가 현재 비밀번호와 동일합니다.");
        }

        user.updatePassword(passwordEncoder.encode(request.getNew_password()));
        log.info("비밀번호 변경 완료 - userId: {}", userId);
    }

    /**
     * 회원탈퇴
     *
     * 삭제 순서 (FK 제약 조건 위반 방지):
     * 1. R2 스토리지 사진 파일 삭제
     * 2. 내가 작성한 댓글 삭제 (Comment.writer_id → User FK 위반 방지)
     * 3. 여행 삭제 (cascade → TravelDay → Photo, TravelLog)
     * 4. 미분류 사진 DB 삭제
     * 5. 유저 삭제
     * 6. 토큰 Redis 블랙리스트 등록 (탈퇴 후 기존 토큰 즉시 무효화)
     *
     * @param token 탈퇴 요청에 사용된 JWT 토큰 (null이면 블랙리스트 등록 건너뜀)
     */
    @Transactional
    public void deleteAccount(Long userId, DeleteAccountRequest request, String token) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // 1. R2 스토리지 사진 파일 삭제 (DB 삭제는 cascade에 맡김)
        List<Photo> allPhotos = photoRepository.findByUserId(userId);
        for (Photo photo : allPhotos) {
            try {
                gcsService.deleteFile(photo.getFilePath());
                log.info("R2 사진 삭제 완료 - photoId: {}", photo.getId());
            } catch (Exception e) {
                log.warn("R2 사진 삭제 실패 (DB 삭제는 계속 진행) - photoId: {}, error: {}", photo.getId(), e.getMessage());
            }
        }

        // 2. 내가 다른 사람 사진에 작성한 댓글 삭제 (writer_id = userId)
        commentRepository.deleteByWriterId(userId);
        log.info("작성한 댓글 삭제 완료 - userId: {}", userId);

        // 3. 여행 전체 삭제 (cascade → TravelDay → Photo, TravelLog)
        List<Travel> travels = travelRepository.findAllByUserOrderByIdDesc(user);
        travelRepository.deleteAll(travels);
        log.info("여행 삭제 완료 - userId: {}, 여행 수: {}", userId, travels.size());

        // 4. 미분류 사진(travelDay=null) DB 삭제
        List<Photo> unassignedPhotos = photoRepository.findByUserIdAndTravelDayIsNull(userId);
        photoRepository.deleteAll(unassignedPhotos);
        log.info("미분류 사진 삭제 완료 - userId: {}, 사진 수: {}", userId, unassignedPhotos.size());

        // 5. 유저 삭제
        userRepository.delete(user);
        log.info("회원탈퇴 완료 - userId: {}, email: {}", userId, user.getEmail());

        // 6. 기존 토큰 Redis 블랙리스트 등록 (탈퇴 후 즉시 무효화)
        //    @Transactional 바깥에서 실행되도록 메서드 마지막에 위치
        //    DB 삭제가 커밋된 이후 블랙리스트에 올라가므로 순서 보장
        if (token != null) {
            logout(token);
            log.info("탈퇴 토큰 블랙리스트 등록 완료 - userId: {}", userId);
        }
    }
}

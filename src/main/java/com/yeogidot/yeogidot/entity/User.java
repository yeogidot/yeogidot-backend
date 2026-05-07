package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

// 유저 엔티티

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users")
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /**
     * 비밀번호가 마지막으로 변경된 시각.
     * JWT 토큰의 iat(발급시각)이 이 값보다 이전이면 해당 토큰을 거부해
     * 비밀번호 변경 시 모든 기기의 토큰을 일괄 무효화한다.
     */
    @Builder.Default
    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt = Instant.now();

    public static User create(String email, String password) {
        User user = new User();
        user.email = email;
        user.password = password;
        return user;
    }

    // 비밀번호 변경 메서드 — 변경 시각도 함께 갱신
    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.passwordChangedAt = Instant.now();
    }
}

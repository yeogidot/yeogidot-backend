package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
/**
 * [사용자] 엔티티
 * - 회원 가입한 유저 정보를 저장합니다.
 */
@Entity
@Getter // ★ 필수
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "user")
@Builder
public class User extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    /**
     * 테스트 및 초기 생성을 위한 팩토리 메서드
     * (Service 등에서 간편하게 User 객체를 생성하기 위함)
     */
    public static User create(String email, String password) {
        User user = new User();
        user.email = email;
        user.password = password;
        return user;
    }
}
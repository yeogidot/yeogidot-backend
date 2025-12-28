package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * [사용자] 엔티티
 * - 회원 가입한 유저 정보를 저장합니다.
 */
@Entity
@Getter
@Table(name = "users") // 'user'는 DB 예약어일 가능성이 높아 'users'로 명명
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

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
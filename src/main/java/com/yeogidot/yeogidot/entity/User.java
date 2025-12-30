package com.yeogidot.yeogidot.entity;

import jakarta.persistence.*;
import lombok.*;

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

    public static User create(String email, String password) {
        User user = new User();
        user.email = email;
        user.password = password;
        return user;
    }
}
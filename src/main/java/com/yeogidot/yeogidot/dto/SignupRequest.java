package com.yeogidot.yeogidot.dto;

import lombok.Getter; // 이게 있어야 함
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter // ★ 이 줄이 없으면 getEmail() 등을 못 씁니다!
@Setter
@NoArgsConstructor
public class SignupRequest {
    private String email;
    private String password;
    private String password_check;
    private Boolean privacy_policy_agreed;
}
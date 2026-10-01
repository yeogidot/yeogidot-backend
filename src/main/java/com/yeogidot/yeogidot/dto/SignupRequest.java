package com.yeogidot.yeogidot.dto;

import lombok.Getter; // 이게 있어야 함
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter // 이 줄이 없으면 getEmail() 등을 못 씁니다!
@Setter
@NoArgsConstructor
public class SignupRequest {
    private String email;
    // 구버전 앱과 기존 가입 계약을 위해 전환 기간에는 생략 가능하다.
    private String nickname;
    private String password;
    private String password_check;
    private Boolean privacy_policy_agreed;
}

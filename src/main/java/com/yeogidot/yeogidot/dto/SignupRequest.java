package com.yeogidot.yeogidot.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SignupRequest {
    private String email;
    private String password;
    private String password_check;
    private Boolean privacy_policy_agreed;
}
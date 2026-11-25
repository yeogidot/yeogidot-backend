package com.yeogidot.yeogidot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter; // 이게 있어야 함
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter // 이 줄이 없으면 getEmail() 등을 못 씁니다!
@Setter
@NoArgsConstructor
public class SignupRequest {
    private String email;
    @Schema(description = "닉네임. 2~20자의 한글, 영문, 숫자, 밑줄을 사용할 수 있습니다.",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "바다여행")
    private String nickname;
    private String password;
    private String password_check;
    private Boolean privacy_policy_agreed;
}

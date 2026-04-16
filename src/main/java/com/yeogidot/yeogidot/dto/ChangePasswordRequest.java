package com.yeogidot.yeogidot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChangePasswordRequest {

    private String password;

    @JsonProperty("new_password")
    private String new_password;
}

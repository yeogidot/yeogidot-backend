package com.yeogidot.yeogidot.dto;

import com.yeogidot.yeogidot.entity.User;

public record MyProfileResponse(Long userId, String email, String nickname, boolean nicknameRequired) {
    public static MyProfileResponse from(User user) {
        return new MyProfileResponse(user.getId(), user.getEmail(), user.getNickname(), user.getNickname() == null);
    }
}

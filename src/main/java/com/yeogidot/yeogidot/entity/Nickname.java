package com.yeogidot.yeogidot.entity;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 표시 이름은 유지하고 중복 비교에는 정규화한 키를 사용한다. */
public record Nickname(String display, String key) {
    private static final Pattern ALLOWED = Pattern.compile("[가-힣a-zA-Z0-9_]{2,20}");

    public static Nickname of(String input) {
        if (input == null) {
            throw new IllegalArgumentException("닉네임을 입력해주세요.");
        }
        String display = Normalizer.normalize(input.strip(), Normalizer.Form.NFC);
        if (!ALLOWED.matcher(display).matches()) {
            throw new IllegalArgumentException("닉네임은 2~20자의 한글, 영문, 숫자, 밑줄만 사용할 수 있습니다.");
        }
        return new Nickname(display, display);
    }
}

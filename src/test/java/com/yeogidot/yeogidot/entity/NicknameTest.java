package com.yeogidot.yeogidot.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.*;

class NicknameTest {
    @Test
    void trimsAndKeepsCaseInBothDisplayAndComparisonKey() {
        assertThat(Nickname.of("  여행_Jinwon1  "))
                .isEqualTo(new Nickname("여행_Jinwon1", "여행_Jinwon1"));
    }

    @Test
    void differentLetterCaseProducesDifferentKeys() {
        assertThat(Nickname.of("Jinwon").key()).isNotEqualTo(Nickname.of("jInwon").key());
    }

    @Test
    void composedAndDecomposedHangulHaveTheSameKey() {
        assertThat(Nickname.of(Normalizer.normalize("여행자", Normalizer.Form.NFD)))
                .isEqualTo(Nickname.of("여행자"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"가나", "ab", "12", "__", "가나다라마바사아자차카타파하가나다라마바"})
    void acceptsValidBoundaries(String input) {
        assertThat(Nickname.of(input).display()).isEqualTo(input);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "a", "abcdefghijklmnopqrstu", "여행 자", "진원!", "abc😀", "ＡＢ", "ab\u200B", "ㄱㄴ"})
    void rejectsInvalidInput(String input) {
        assertThatThrownBy(() -> Nickname.of(input)).isInstanceOf(IllegalArgumentException.class);
    }
}

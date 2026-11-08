package com.shg.trip.shgtrip.domain.user.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileUpdateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("앞뒤 공백은 잘라내고 길이를 잰다")
    void stripsBeforeValidation() {
        ProfileUpdateRequest padded = new ProfileUpdateRequest("  ab  ");
        assertThat(padded.nickname()).isEqualTo("ab");
        assertThat(validator.validate(padded)).isEmpty();

        assertThat(validator.validate(new ProfileUpdateRequest("  a  "))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("   "))).isNotEmpty();
    }

    @Test
    @DisplayName("줄바꿈·NUL 등 제어 문자는 400으로 거부한다")
    void rejectsControlCharacters() {
        assertThat(validator.validate(new ProfileUpdateRequest("ab\ncd"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("ab\u0000cd"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("ab\tcd"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("여행자 01"))).isEmpty();
    }

    @Test
    @DisplayName("제로폭·방향 제어 문자는 거부하고 공백은 하나로 합쳐 같은 모양의 다른 닉네임을 막는다")
    void rejectsLookalikeNicknames() {
        assertThat(validator.validate(new ProfileUpdateRequest("b\u200Byu"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("\u200B\u200B"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("ab\u202Ecd"))).isNotEmpty();

        assertThat(new ProfileUpdateRequest("여행자     01").nickname()).isEqualTo("여행자 01");
        assertThat(new ProfileUpdateRequest("여행자\u3000\u00A001").nickname()).isEqualTo("여행자 01");
        // 한글 자모 분리형(NFD)도 완성형과 같은 값으로 저장된다
        assertThat(new ProfileUpdateRequest("\u1100\u1161\u1102\u1161").nickname()).isEqualTo("가나");
    }

    @Test
    @DisplayName("한글 채움 문자·점자 공백·결합 기호처럼 글자 분류인 투명 문자도 거부한다")
    void rejectsInvisibleLetters() {
        assertThat(validator.validate(new ProfileUpdateRequest("\u3164\u3164"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("여행자\u3164"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("ab\u115F"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("\u2800\u2800"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("ab\uFE0F"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("!!"))).isNotEmpty();
        assertThat(validator.validate(new ProfileUpdateRequest("여행자_01"))).isEmpty();
    }
}

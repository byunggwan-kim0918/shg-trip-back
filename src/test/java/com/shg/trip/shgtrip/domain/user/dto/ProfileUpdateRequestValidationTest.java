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
}

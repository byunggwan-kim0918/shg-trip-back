package com.shg.trip.shgtrip.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * startDate < endDate를 검증한다. 클래스 레벨에 붙이고 startDateField / endDateField로 필드명을 지정한다.
 */
@Documented
@Constraint(validatedBy = DateRangeValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDateRange {
    String message() default "종료일은 시작일보다 이후여야 합니다.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    String startDateField() default "startDate";
    String endDateField() default "endDate";

    /**
     * 허용 최대 여행 일수(당일 포함). 0이면 무제한.
     * 후보 풀 상한(MAX_TOTAL=80)에 비해 기간이 길면 후반부 일정 품질이 무너진다.
     */
    int maxDays() default 0;
}

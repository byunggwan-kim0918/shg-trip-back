package com.shg.trip.shgtrip.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * startDate < endDate 검증 어노테이션.
 * 클래스 레벨에 적용하며, startDateField / endDateField로 필드명을 지정합니다.
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
     * 후보 풀 상한(MAX_TOTAL=80) 대비 과도한 기간은 후반부 일정 품질이 붕괴하므로 상한을 둔다.
     */
    int maxDays() default 0;
}

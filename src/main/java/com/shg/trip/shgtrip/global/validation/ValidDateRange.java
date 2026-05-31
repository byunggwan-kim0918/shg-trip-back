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
}

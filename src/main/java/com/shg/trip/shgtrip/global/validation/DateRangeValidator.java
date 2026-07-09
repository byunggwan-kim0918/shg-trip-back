package com.shg.trip.shgtrip.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapperImpl;

import java.time.LocalDate;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, Object> {

    private String startDateField;
    private String endDateField;
    private int maxDays;

    @Override
    public void initialize(ValidDateRange annotation) {
        this.startDateField = annotation.startDateField();
        this.endDateField = annotation.endDateField();
        this.maxDays = annotation.maxDays();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;

        BeanWrapperImpl wrapper = new BeanWrapperImpl(value);
        Object start = wrapper.getPropertyValue(startDateField);
        Object end = wrapper.getPropertyValue(endDateField);

        if (start == null || end == null) return true; // @NotNull이 별도 처리

        if (start instanceof LocalDate startDate && end instanceof LocalDate endDate) {
            if (!endDate.isAfter(startDate)) {
                addViolation(context, context.getDefaultConstraintMessageTemplate());
                return false;
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (maxDays > 0 && days > maxDays) {
                addViolation(context, "여행 기간은 최대 " + maxDays + "일까지 선택할 수 있습니다.");
                return false;
            }
            return true;
        }
        return true;
    }

    private void addViolation(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(endDateField)
                .addConstraintViolation();
    }
}

package com.shg.trip.shgtrip.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapperImpl;

import java.time.LocalDate;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, Object> {

    private String startDateField;
    private String endDateField;

    @Override
    public void initialize(ValidDateRange annotation) {
        this.startDateField = annotation.startDateField();
        this.endDateField = annotation.endDateField();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;

        BeanWrapperImpl wrapper = new BeanWrapperImpl(value);
        Object start = wrapper.getPropertyValue(startDateField);
        Object end = wrapper.getPropertyValue(endDateField);

        if (start == null || end == null) return true; // @NotNull이 별도 처리

        if (start instanceof LocalDate startDate && end instanceof LocalDate endDate) {
            boolean valid = endDate.isAfter(startDate);
            if (!valid) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                        .addPropertyNode(endDateField)
                        .addConstraintViolation();
            }
            return valid;
        }
        return true;
    }
}

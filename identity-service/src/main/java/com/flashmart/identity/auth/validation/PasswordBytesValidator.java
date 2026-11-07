package com.flashmart.identity.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class PasswordBytesValidator implements ConstraintValidator<PasswordBytes, String> {

    private int min;
    private int max;

    @Override
    public void initialize(PasswordBytes annotation) {
        this.min = annotation.min();
        this.max = annotation.max();
    }

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= min && bytes <= max;
    }
}

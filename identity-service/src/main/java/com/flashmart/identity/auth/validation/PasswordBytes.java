package com.flashmart.identity.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Password length counted in UTF-8 BYTES, not characters.
 *
 * <p>bcrypt reads at most 72 bytes and silently ignores the rest, so a
 * 100-byte password would really be its first 72 bytes. {@code @Size} counts
 * characters: {@code "é"} is one character but two bytes, so 40 of them pass
 * {@code @Size(max = 72)} while being 80 bytes. Null is invalid (a password is
 * required).
 */
@Documented
@Constraint(validatedBy = PasswordBytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordBytes {

    int min() default 8;

    int max() default 72;

    String message() default "password must be 8 to 72 bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

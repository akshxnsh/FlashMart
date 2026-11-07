package com.flashmart.identity.auth;

import com.flashmart.identity.auth.dto.ErrorCode;
import com.flashmart.identity.auth.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps auth failures to {@code { code, message }}. Scoped to
 * {@link AuthController} so later controllers do not inherit auth codes, the
 * same rule as Inventory's reserve handler. Messages name fields, never their
 * values: a password must never appear in a response or a log line. Anything
 * not mapped falls through to Spring's default 500.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidField(MethodArgumentNotValidException ex) {
        List<String> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .toList();
        return new ErrorResponse(ErrorCode.INVALID_REQUEST, "invalid fields: " + fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadableBody(HttpMessageNotReadableException ex) {
        return new ErrorResponse(ErrorCode.INVALID_REQUEST, "request body could not be read");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return new ErrorResponse(ErrorCode.INVALID_REQUEST, "unsupported content type: " + ex.getContentType());
    }

    @ExceptionHandler(EmailTakenException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse emailTaken(EmailTakenException ex) {
        return new ErrorResponse(ErrorCode.EMAIL_TAKEN, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse invalidCredentials(InvalidCredentialsException ex) {
        return new ErrorResponse(ErrorCode.INVALID_CREDENTIALS, ex.getMessage());
    }
}

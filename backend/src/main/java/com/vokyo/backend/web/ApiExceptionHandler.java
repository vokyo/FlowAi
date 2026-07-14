package com.vokyo.backend.web;

import com.vokyo.backend.security.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiErrorService errorService;
    private final ApiObservability observability;

    public ApiExceptionHandler(ApiErrorService errorService, ApiObservability observability) {
        this.errorService = errorService;
        this.observability = observability;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                bindingErrors(exception.getBindingResult())
        ));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiErrorResponse> handleBindException(
            BindException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                bindingErrors(exception.getBindingResult())
        ));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                parameterValidationErrors(
                        exception.getParameterValidationResults(),
                        exception.getCrossParameterValidationResults()
                )
        ));
    }

    @ExceptionHandler(MethodValidationException.class)
    ResponseEntity<ApiErrorResponse> handleMethodValidation(
            MethodValidationException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                parameterValidationErrors(
                        exception.getParameterValidationResults(),
                        exception.getCrossParameterValidationResults()
                )
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String path = violation.getPropertyPath() == null
                    ? "request"
                    : violation.getPropertyPath().toString();
            fieldErrors.putIfAbsent(path, safeMessage(violation.getMessage()));
        }
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                fieldErrors
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                Map.of(exception.getParameterName(), "Parameter is required")
        ));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        String name = exception.getName() == null ? "request" : exception.getName();
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                "Request contains an invalid value",
                Map.of(name, "Value is invalid")
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, errorService.create(
                request,
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                "Request body is invalid"
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiErrorResponse> handleBadCredentials(
            BadCredentialsException exception,
            HttpServletRequest request
    ) {
        observability.recordAuthenticationFailure("credentials");
        return response(HttpStatus.UNAUTHORIZED, errorService.create(
                request,
                HttpStatus.UNAUTHORIZED.value(),
                "AUTHENTICATION_FAILED",
                "Invalid email or password"
        ));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrorResponse> handleAuthentication(
            AuthenticationException exception,
            HttpServletRequest request
    ) {
        observability.recordAuthenticationFailure("authentication");
        return response(HttpStatus.UNAUTHORIZED, errorService.create(
                request,
                HttpStatus.UNAUTHORIZED.value(),
                "AUTHENTICATION_REQUIRED",
                "Authentication is required"
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.FORBIDDEN, errorService.create(
                request,
                HttpStatus.FORBIDDEN.value(),
                "ACCESS_DENIED",
                "Access is denied"
        ));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiErrorResponse> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletRequest request
    ) {
        ApiErrorResponse body = errorService.create(
                request,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "RATE_LIMITED",
                "Too many requests"
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .header(TraceIds.HEADER, body.traceId())
                .body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.CONFLICT, errorService.create(
                request,
                HttpStatus.CONFLICT.value(),
                "DATABASE_CONFLICT",
                "The request conflicts with existing data"
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason();
        if (message == null || message.isBlank()) {
            message = status instanceof HttpStatus httpStatus
                    ? httpStatus.getReasonPhrase()
                    : "Request failed";
        }
        if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
            observability.recordAuthenticationFailure("session");
        }
        return response(status, errorService.create(
                request,
                status.value(),
                responseStatusCode(exception),
                message
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, errorService.create(
                request,
                HttpStatus.NOT_FOUND.value(),
                "RESOURCE_NOT_FOUND",
                "Resource not found"
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, errorService.create(
                request,
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "METHOD_NOT_ALLOWED",
                "Request method is not supported"
        ));
    }

    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiErrorResponse> handleSpringErrorResponse(
            ErrorResponseException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode status = exception.getStatusCode();
        return response(status, errorService.create(
                request,
                status.value(),
                statusCode(status),
                "Request failed"
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnknown(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "event=api_exception exceptionType={}",
                exception.getClass().getName()
        );
        return response(HttpStatus.INTERNAL_SERVER_ERROR, errorService.create(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "An unexpected error occurred"
        ));
    }

    private Map<String, String> bindingErrors(BindingResult bindingResult) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    safeMessage(fieldError.getDefaultMessage())
            );
        }
        for (ObjectError globalError : bindingResult.getGlobalErrors()) {
            fieldErrors.putIfAbsent("request", safeMessage(globalError.getDefaultMessage()));
        }
        return fieldErrors;
    }

    private Map<String, String> parameterValidationErrors(
            Iterable<ParameterValidationResult> validationResults,
            Iterable<? extends MessageSourceResolvable> crossParameterErrors
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ParameterValidationResult result : validationResults) {
            MethodParameter parameter = result.getMethodParameter();
            String name = parameter.getParameterName() == null
                    ? "request"
                    : parameter.getParameterName();
            result.getResolvableErrors().stream()
                    .findFirst()
                    .map(error -> safeMessage(error.getDefaultMessage()))
                    .ifPresent(message -> fieldErrors.putIfAbsent(name, message));
        }
        for (MessageSourceResolvable error : crossParameterErrors) {
            fieldErrors.putIfAbsent("request", safeMessage(error.getDefaultMessage()));
        }
        return fieldErrors;
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Value is invalid" : message;
    }

    private String responseStatusCode(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                && "Invalid cursor".equals(exception.getReason())) {
            return "INVALID_CURSOR";
        }
        return statusCode(exception.getStatusCode());
    }

    private String statusCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "AUTHENTICATION_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 410 -> "RESOURCE_GONE";
            case 429 -> "RATE_LIMITED";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status,
            ApiErrorResponse body
    ) {
        return ResponseEntity.status(status)
                .header(TraceIds.HEADER, body.traceId())
                .body(body);
    }
}

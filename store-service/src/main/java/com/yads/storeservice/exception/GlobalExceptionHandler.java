package com.yads.storeservice.exception;

import com.yads.storeservice.dto.ErrorResponse;
import com.yads.storeservice.dto.ValidationErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Generate a unique correlation ID for tracking requests
     */
    private String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Handles ResourceNotFoundException (404 - Not Found)
     * Thrown when a requested resource (Store, Category, Product) is not found
     * Note: Logging is done at service layer with more context
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // No logging here - service layer has better context

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .error(HttpStatus.NOT_FOUND.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("RESOURCE_NOT_FOUND")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    /**
     * Handles AccessDeniedException (403 - Forbidden)
     * Thrown when user tries to access/modify a resource they don't own
     * Note: Logging is done at service layer with more context (user ID, resource ID, owner ID)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // No logging here - service layer has better context with IDs

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("ACCESS_DENIED")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.FORBIDDEN);
    }

    /**
     * Handles DuplicateResourceException (409 - Conflict)
     * Thrown when trying to create a resource that already exists
     * Note: Logging is done at service layer with more context
     */
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateResourceException(
            DuplicateResourceException ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // No logging here - service layer has better context

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .error(HttpStatus.CONFLICT.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("DUPLICATE_RESOURCE")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.CONFLICT);
    }

    /**
     * Handles MethodArgumentNotValidException (400 - Bad Request)
     * Thrown when request validation fails (@Valid annotation)
     * Returns all field validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> validationErrors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                validationErrors.put(fieldError.getField(), error.getDefaultMessage());
            }
        });

        String correlationId = generateCorrelationId();
        log.debug("[{}] Validation failed - Path: {} - Errors: {}", correlationId, request.getRequestURI(), validationErrors);

        ValidationErrorResponse errorResponse = ValidationErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .message("Validation failed")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .validationErrors(validationErrors)
                .errorCode("VALIDATION_FAILED")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles InsufficientStockException (422 - Unprocessable Entity)
     * Thrown when there is not enough stock for a product operation
     * Note: Logging is done at service layer with more context (product ID, available stock, requested quantity)
     */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStockException(
            InsufficientStockException ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // No logging here - service layer has better context with stock details

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .error(HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("INSUFFICIENT_STOCK")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * Handles IllegalArgumentException (400 - Bad Request)
     * Thrown when method receives invalid arguments
     * Note: Logging is done at service layer with more context
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // No logging here - service layer has better context

        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("INVALID_ARGUMENT")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    /**
     * Handles all other unexpected exceptions (500 - Internal Server Error)
     * Generic handler for any unhandled exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(
            Exception ex,
            HttpServletRequest request) {

        String correlationId = generateCorrelationId();
        // Log the full exception with stack trace for debugging
        log.error("[{}] Unexpected error occurred - Path: {} - Exception: {}",
                correlationId,
                request.getRequestURI(),
                ex.getMessage(),
                ex);

        // Return generic message to client (avoid exposing internal details)
        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message("An unexpected error occurred. Please contact support if the problem persists.")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .errorCode("INTERNAL_SERVER_ERROR")
                .correlationId(correlationId)
                .build();

        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

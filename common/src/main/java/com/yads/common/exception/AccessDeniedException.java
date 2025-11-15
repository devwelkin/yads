package com.yads.common.exception;

/**
 * Exception thrown when a user tries to access/modify a resource they don't own
 * HTTP Status: 403 Forbidden (set in GlobalExceptionHandler)
 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}



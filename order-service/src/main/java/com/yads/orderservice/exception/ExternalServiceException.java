package com.yads.orderservice.exception;

/**
 * Exception thrown when communication with external services fails
 * For example: store-service is unavailable or returns an error
 * HTTP Status: 502 Bad Gateway
 */
public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}



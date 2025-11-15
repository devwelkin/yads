package com.yads.common.exception;

/**
 * Exception thrown when there is insufficient stock for a product
 * HTTP Status: 422 Unprocessable Entity
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }

    public InsufficientStockException(String message, Throwable cause) {
        super(message, cause);
    }
}



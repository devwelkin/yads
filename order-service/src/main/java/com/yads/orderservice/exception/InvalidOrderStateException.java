package com.yads.orderservice.exception;

/**
 * Exception thrown when an order state transition is invalid
 * For example: trying to deliver an order that is still PENDING
 * HTTP Status: 422 Unprocessable Entity
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }

    public InvalidOrderStateException(String message, Throwable cause) {
        super(message, cause);
    }
}



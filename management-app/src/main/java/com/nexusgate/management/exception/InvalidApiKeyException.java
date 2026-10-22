package com.nexusgate.management.exception;

public class InvalidApiKeyException extends RuntimeException {
    public InvalidApiKeyException(String message) {
        super(message);
    }
}

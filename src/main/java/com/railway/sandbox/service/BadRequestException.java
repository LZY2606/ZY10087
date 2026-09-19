package com.railway.sandbox.service;

public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}

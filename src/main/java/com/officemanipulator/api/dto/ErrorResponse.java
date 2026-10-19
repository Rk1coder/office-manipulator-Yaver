package com.officemanipulator.api.dto;

public record ErrorResponse(String error) {
    public static ErrorResponse of(String message) {
        return new ErrorResponse(message);
    }
}

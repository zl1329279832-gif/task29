package com.visitor.common.exception;

public class DuplicateAppointmentException extends BusinessException {
    public DuplicateAppointmentException(String message) {
        super(409, message);
    }
}

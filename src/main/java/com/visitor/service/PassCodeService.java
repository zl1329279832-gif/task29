package com.visitor.service;

import com.visitor.dto.response.PassCodeResponse;

public interface PassCodeService {
    PassCodeResponse generate(Long appointmentId);
    PassCodeResponse verify(String code);
    void revoke(Long id);
    PassCodeResponse getByAppointmentId(Long appointmentId);
}

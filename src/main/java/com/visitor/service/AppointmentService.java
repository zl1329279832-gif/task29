package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.AppointmentCreateRequest;
import com.visitor.dto.request.AppointmentRescheduleRequest;
import com.visitor.dto.request.AppointmentUpdateRequest;
import com.visitor.dto.response.AppointmentResponse;

public interface AppointmentService {
    AppointmentResponse create(AppointmentCreateRequest request);
    AppointmentResponse getById(Long id);
    PageResult<AppointmentResponse> list(String status, int page, int size);
    AppointmentResponse update(Long id, AppointmentUpdateRequest request);
    void cancel(Long id);
    AppointmentResponse reschedule(Long id, AppointmentRescheduleRequest request);
    boolean checkDuplicate(String visitorPhone, String visitStartTime, String visitEndTime);
}

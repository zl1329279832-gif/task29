package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.ApprovalRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.dto.response.ApprovalResponse;

public interface ApprovalService {
    void approve(Long appointmentId, ApprovalRequest request);
    void reject(Long appointmentId, ApprovalRequest request);
    PageResult<AppointmentResponse> getPendingList(int page, int size);
    PageResult<ApprovalResponse> getHistory(int page, int size);
}

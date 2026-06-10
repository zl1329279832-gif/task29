package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.CheckInRequest;
import com.visitor.dto.request.CheckOutRequest;
import com.visitor.dto.response.CheckInResponse;

public interface CheckInService {
    CheckInResponse checkIn(CheckInRequest request);
    CheckInResponse checkOut(CheckOutRequest request);
    PageResult<CheckInResponse> getCurrentVisitors(int page, int size);
    PageResult<CheckInResponse> getRecords(Long visitorId, Long appointmentId, int page, int size);
}

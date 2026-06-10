package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.AbnormalPassRequest;
import com.visitor.dto.response.AbnormalPassResponse;

public interface AbnormalPassService {
    AbnormalPassResponse record(AbnormalPassRequest request);
    PageResult<AbnormalPassResponse> list(String type, Integer handled, int page, int size);
    void handle(Long id, String handleRemark);
}

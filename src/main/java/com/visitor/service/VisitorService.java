package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.VisitorRegisterRequest;
import com.visitor.dto.response.VisitorResponse;

public interface VisitorService {
    VisitorResponse register(VisitorRegisterRequest request);
    VisitorResponse getById(Long id);
    VisitorResponse update(Long id, VisitorRegisterRequest request);
    PageResult<VisitorResponse> list(int page, int size);
    PageResult<VisitorResponse> search(String keyword, int page, int size);
}

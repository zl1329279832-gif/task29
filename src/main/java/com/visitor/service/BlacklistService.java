package com.visitor.service;

import com.visitor.common.result.PageResult;
import com.visitor.dto.request.BlacklistRequest;
import com.visitor.dto.response.BlacklistResponse;

public interface BlacklistService {
    BlacklistResponse add(BlacklistRequest request);
    void remove(Long id);
    PageResult<BlacklistResponse> list(int page, int size);
    boolean isBlacklisted(String phone, String idCard);
    void refreshCache();
}

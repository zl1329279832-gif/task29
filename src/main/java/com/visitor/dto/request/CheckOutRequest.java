package com.visitor.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CheckOutRequest {
    @NotNull(message = "签到记录ID不能为空")
    private Long checkInRecordId;
    private String gateId;
    private String remark;
}

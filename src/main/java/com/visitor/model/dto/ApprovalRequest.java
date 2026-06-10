package com.visitor.model.dto;

import com.visitor.model.enums.ApprovalActionEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ApprovalRequest {
    @NotNull(message = "审批动作不能为空")
    private ApprovalActionEnum action;
    private String remark;
}

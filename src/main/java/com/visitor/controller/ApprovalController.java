package com.visitor.controller;

import com.visitor.model.dto.ApprovalRequest;
import com.visitor.model.enums.ApprovalActionEnum;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.ApprovalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/{appointmentId}/process")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> process(@PathVariable Long appointmentId,
                                      @Valid @RequestBody ApprovalRequest request) {
        approvalService.processApproval(appointmentId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{appointmentId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> approve(@PathVariable Long appointmentId,
                                      @RequestParam(required = false) String remark) {
        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.APPROVE);
        request.setRemark(remark);
        approvalService.processApproval(appointmentId, request);
        return ApiResponse.success();
    }

    @PostMapping("/{appointmentId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> reject(@PathVariable Long appointmentId,
                                     @RequestBody(required = false) Map<String, String> body) {
        ApprovalRequest request = new ApprovalRequest();
        request.setAction(ApprovalActionEnum.REJECT);
        request.setRemark(body != null ? body.get("remark") : null);
        approvalService.processApproval(appointmentId, request);
        return ApiResponse.success();
    }
}

package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.ApprovalRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.dto.response.ApprovalResponse;
import com.visitor.service.ApprovalService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping("/pending")
    public Result<PageResult<AppointmentResponse>> getPendingList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(approvalService.getPendingList(page, size));
    }

    @PostMapping("/{appointmentId}/approve")
    public Result<Void> approve(@PathVariable Long appointmentId, @RequestBody ApprovalRequest request) {
        approvalService.approve(appointmentId, request);
        return Result.ok();
    }

    @PostMapping("/{appointmentId}/reject")
    public Result<Void> reject(@PathVariable Long appointmentId, @RequestBody ApprovalRequest request) {
        approvalService.reject(appointmentId, request);
        return Result.ok();
    }

    @GetMapping("/history")
    public Result<PageResult<ApprovalResponse>> getHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(approvalService.getHistory(page, size));
    }
}

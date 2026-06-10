package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.AnomalyReleaseRequest;
import com.visitor.model.dto.GateCheckinRequest;
import com.visitor.model.dto.GateCheckoutRequest;
import com.visitor.model.dto.GateScanRequest;
import com.visitor.model.entity.AccessLog;
import com.visitor.model.vo.AccessLogVO;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.GateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gate")
@RequiredArgsConstructor
public class GateController {

    private final GateService gateService;

    @PostMapping("/checkin")
    public ApiResponse<AccessLog> checkin(@Valid @RequestBody GateCheckinRequest request) {
        return ApiResponse.success(gateService.checkin(request));
    }

    @PostMapping("/checkout")
    public ApiResponse<AccessLog> checkout(@Valid @RequestBody GateCheckoutRequest request) {
        return ApiResponse.success(gateService.checkout(request));
    }

    @GetMapping("/logs")
    public ApiResponse<Page<AccessLogVO>> logs(
            @RequestParam(required = false) Long visitorId,
            @RequestParam(required = false) Long appointmentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(gateService.getAccessLogs(visitorId, appointmentId, page, size));
    }

    @PostMapping("/anomaly-release")
    @PreAuthorize("hasRole('SECURITY') or hasRole('ADMIN')")
    public ApiResponse<AccessLog> anomalyRelease(@Valid @RequestBody AnomalyReleaseRequest request) {
        return ApiResponse.success(gateService.anomalyRelease(request));
    }

    @PostMapping("/pass-through")
    public ApiResponse<AccessLog> passThrough(@Valid @RequestBody GateScanRequest request) {
        return ApiResponse.success(gateService.passThroughGate(request));
    }
}

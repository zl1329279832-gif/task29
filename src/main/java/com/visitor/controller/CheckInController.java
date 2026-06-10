package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.CheckInRequest;
import com.visitor.dto.request.CheckOutRequest;
import com.visitor.dto.response.CheckInResponse;
import com.visitor.service.CheckInService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('SECURITY', 'ADMIN')")
public class CheckInController {

    private final CheckInService checkInService;

    public CheckInController(CheckInService checkInService) {
        this.checkInService = checkInService;
    }

    @PostMapping("/check-in")
    public Result<CheckInResponse> checkIn(@Valid @RequestBody CheckInRequest request) {
        return Result.ok(checkInService.checkIn(request));
    }

    @PostMapping("/check-out")
    public Result<CheckInResponse> checkOut(@Valid @RequestBody CheckOutRequest request) {
        return Result.ok(checkInService.checkOut(request));
    }

    @GetMapping("/check-in/current")
    public Result<PageResult<CheckInResponse>> getCurrentVisitors(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(checkInService.getCurrentVisitors(page, size));
    }

    @GetMapping("/check-in/records")
    public Result<PageResult<CheckInResponse>> getRecords(
            @RequestParam(required = false) Long visitorId,
            @RequestParam(required = false) Long appointmentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(checkInService.getRecords(visitorId, appointmentId, page, size));
    }
}

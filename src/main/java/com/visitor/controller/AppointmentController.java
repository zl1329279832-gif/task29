package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.AppointmentCreateRequest;
import com.visitor.dto.request.AppointmentRescheduleRequest;
import com.visitor.dto.request.AppointmentUpdateRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public Result<AppointmentResponse> create(@Valid @RequestBody AppointmentCreateRequest request) {
        return Result.ok(appointmentService.create(request));
    }

    @GetMapping("/{id}")
    public Result<AppointmentResponse> getById(@PathVariable Long id) {
        return Result.ok(appointmentService.getById(id));
    }

    @GetMapping
    public Result<PageResult<AppointmentResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(appointmentService.list(status, page, size));
    }

    @PutMapping("/{id}")
    public Result<AppointmentResponse> update(@PathVariable Long id,
                                               @Valid @RequestBody AppointmentUpdateRequest request) {
        return Result.ok(appointmentService.update(id, request));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        appointmentService.cancel(id);
        return Result.ok();
    }

    @PostMapping("/{id}/reschedule")
    public Result<AppointmentResponse> reschedule(@PathVariable Long id,
                                                   @Valid @RequestBody AppointmentRescheduleRequest request) {
        return Result.ok(appointmentService.reschedule(id, request));
    }

    @GetMapping("/check-duplicate")
    public Result<Boolean> checkDuplicate(@RequestParam String visitorPhone,
                                           @RequestParam String visitStartTime,
                                           @RequestParam String visitEndTime) {
        return Result.ok(appointmentService.checkDuplicate(visitorPhone, visitStartTime, visitEndTime));
    }
}

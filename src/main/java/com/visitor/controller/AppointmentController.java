package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.AppointmentCreateRequest;
import com.visitor.model.dto.AppointmentRescheduleRequest;
import com.visitor.model.entity.Appointment;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.AppointmentVO;
import com.visitor.service.AppointmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping
    public ApiResponse<Appointment> create(@Valid @RequestBody AppointmentCreateRequest request) {
        return ApiResponse.success(appointmentService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<AppointmentVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(appointmentService.list(status, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppointmentVO> detail(@PathVariable Long id) {
        return ApiResponse.success(appointmentService.getDetail(id));
    }

    @PutMapping("/{id}/reschedule")
    public ApiResponse<Appointment> reschedule(@PathVariable Long id,
                                                @Valid @RequestBody AppointmentRescheduleRequest request) {
        return ApiResponse.success(appointmentService.reschedule(id, request));
    }

    @PutMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        appointmentService.cancel(id);
        return ApiResponse.success();
    }
}

package com.visitor.controller;

import com.visitor.model.dto.AreaAuthorizationRequest;
import com.visitor.model.entity.AreaAuthorization;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.AreaAuthorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/area-authorizations")
@RequiredArgsConstructor
public class AreaAuthorizationController {

    private final AreaAuthorizationService areaAuthorizationService;

    @PostMapping
    public ApiResponse<AreaAuthorization> createAuthorization(
            @Valid @RequestBody AreaAuthorizationRequest request) {
        return ApiResponse.success(areaAuthorizationService.createAuthorization(
                request.getAppointmentId(), request.getAreaId(),
                request.getValidFrom(), request.getValidTo()));
    }

    @GetMapping("/appointment/{appointmentId}")
    public ApiResponse<List<AreaAuthorization>> getByAppointment(
            @PathVariable Long appointmentId) {
        return ApiResponse.success(areaAuthorizationService.getAuthorizedAreas(appointmentId));
    }

    @DeleteMapping("/appointment/{appointmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> revokeByAppointment(@PathVariable Long appointmentId) {
        areaAuthorizationService.revokeByAppointmentId(appointmentId);
        return ApiResponse.success();
    }
}

package com.visitor.controller;

import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.AreaAuthorizationVO;
import com.visitor.service.AreaAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/area-auth")
@RequiredArgsConstructor
public class AreaAuthorizationController {

    private final AreaAuthorizationService areaAuthorizationService;

    @GetMapping("/appointment/{appointmentId}")
    public ApiResponse<List<AreaAuthorizationVO>> getByAppointment(@PathVariable Long appointmentId) {
        return ApiResponse.success(areaAuthorizationService.getByAppointment(appointmentId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('SECURITY')")
    public ApiResponse<Void> revoke(@PathVariable Long id) {
        areaAuthorizationService.revokeById(id);
        return ApiResponse.success(null);
    }
}

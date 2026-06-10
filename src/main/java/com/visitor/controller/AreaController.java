package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.AreaCreateRequest;
import com.visitor.model.dto.GateCreateRequest;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.Gate;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.AreaVO;
import com.visitor.model.vo.GateVO;
import com.visitor.service.AreaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/areas")
@RequiredArgsConstructor
public class AreaController {

    private final AreaService areaService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Area> createArea(@Valid @RequestBody AreaCreateRequest request) {
        return ApiResponse.success(areaService.createArea(request));
    }

    @GetMapping
    public ApiResponse<Page<Area>> listAreas(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(areaService.listAreas(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AreaVO> areaDetail(@PathVariable Long id) {
        return ApiResponse.success(areaService.getAreaDetail(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> disableArea(@PathVariable Long id) {
        areaService.disableArea(id);
        return ApiResponse.success();
    }

    @PostMapping("/gates")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Gate> createGate(@Valid @RequestBody GateCreateRequest request) {
        return ApiResponse.success(areaService.createGate(request));
    }

    @GetMapping("/gates")
    public ApiResponse<List<GateVO>> listGates(
            @RequestParam(required = false) Long areaId) {
        return ApiResponse.success(areaService.listGatesByArea(areaId));
    }

    @GetMapping("/gates/{gateId}")
    public ApiResponse<GateVO> gateDetail(@PathVariable Long gateId) {
        return ApiResponse.success(areaService.getGateDetail(gateId));
    }

    @DeleteMapping("/gates/{gateId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> disableGate(@PathVariable Long gateId) {
        areaService.disableGate(gateId);
        return ApiResponse.success();
    }
}

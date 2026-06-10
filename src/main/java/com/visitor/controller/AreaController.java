package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.AreaCreateRequest;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.VisitorTrajectory;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.AreaVO;
import com.visitor.service.AreaService;
import com.visitor.service.TrajectoryService;
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
    private final TrajectoryService trajectoryService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Area> create(@Valid @RequestBody AreaCreateRequest request) {
        return ApiResponse.success(areaService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<AreaVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(areaService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<AreaVO> detail(@PathVariable Long id) {
        return ApiResponse.success(areaService.getDetail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Area> update(@PathVariable Long id,
                                     @Valid @RequestBody AreaCreateRequest request) {
        return ApiResponse.success(areaService.update(id, request));
    }

    @GetMapping("/{id}/visitors")
    public ApiResponse<List<VisitorTrajectory>> currentVisitors(@PathVariable Long id) {
        return ApiResponse.success(trajectoryService.getCurrentAreaVisitors(id));
    }
}

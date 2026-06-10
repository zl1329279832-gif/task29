package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.GateCreateRequest;
import com.visitor.model.entity.Gate;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.GateVO;
import com.visitor.service.GateManageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/gates")
@RequiredArgsConstructor
public class GateManageController {

    private final GateManageService gateManageService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Gate> create(@Valid @RequestBody GateCreateRequest request) {
        return ApiResponse.success(gateManageService.create(request));
    }

    @GetMapping
    public ApiResponse<Page<GateVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(gateManageService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<GateVO> detail(@PathVariable Long id) {
        return ApiResponse.success(gateManageService.getDetail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Gate> update(@PathVariable Long id,
                                     @Valid @RequestBody GateCreateRequest request) {
        return ApiResponse.success(gateManageService.update(id, request));
    }
}

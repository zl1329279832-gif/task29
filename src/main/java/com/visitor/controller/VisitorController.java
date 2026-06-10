package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.VisitorRegisterRequest;
import com.visitor.model.entity.Visitor;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.VisitorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/visitors")
@RequiredArgsConstructor
public class VisitorController {

    private final VisitorService visitorService;

    @PostMapping
    public ApiResponse<Visitor> register(@Valid @RequestBody VisitorRegisterRequest request) {
        return ApiResponse.success(visitorService.registerOrFind(request));
    }

    @GetMapping
    public ApiResponse<Page<Visitor>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(visitorService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<Visitor> detail(@PathVariable Long id) {
        return ApiResponse.success(visitorService.getById(id));
    }
}

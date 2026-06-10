package com.visitor.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.model.dto.BlacklistRequest;
import com.visitor.model.entity.Blacklist;
import com.visitor.model.vo.ApiResponse;
import com.visitor.service.BlacklistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/blacklist")
@RequiredArgsConstructor
public class BlacklistController {

    private final BlacklistService blacklistService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Blacklist> add(@Valid @RequestBody BlacklistRequest request) {
        return ApiResponse.success(blacklistService.add(request));
    }

    @GetMapping
    public ApiResponse<Page<Blacklist>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(blacklistService.list(keyword, page, size));
    }

    @PostMapping("/check")
    public ApiResponse<Blacklist> check(@RequestParam(required = false) String name,
                                         @RequestParam(required = false) String idCard,
                                         @RequestParam(required = false) String phone) {
        Blacklist bl = blacklistService.check(name, idCard, phone);
        return ApiResponse.success(bl);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        blacklistService.remove(id);
        return ApiResponse.success();
    }
}

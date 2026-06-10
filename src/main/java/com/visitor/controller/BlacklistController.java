package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.BlacklistRequest;
import com.visitor.dto.response.BlacklistResponse;
import com.visitor.service.BlacklistService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/blacklist")
public class BlacklistController {

    private final BlacklistService blacklistService;

    public BlacklistController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    @PostMapping
    public Result<BlacklistResponse> add(@Valid @RequestBody BlacklistRequest request) {
        return Result.ok(blacklistService.add(request));
    }

    @GetMapping
    public Result<PageResult<BlacklistResponse>> list(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(blacklistService.list(page, size));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> remove(@PathVariable Long id) {
        blacklistService.remove(id);
        return Result.ok();
    }

    @GetMapping("/check")
    public Result<Boolean> check(@RequestParam(required = false) String phone,
                                  @RequestParam(required = false) String idCard) {
        return Result.ok(blacklistService.isBlacklisted(phone, idCard));
    }
}

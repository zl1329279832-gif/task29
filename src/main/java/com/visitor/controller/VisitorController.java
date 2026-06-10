package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.VisitorRegisterRequest;
import com.visitor.dto.response.VisitorResponse;
import com.visitor.service.VisitorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/visitors")
public class VisitorController {

    private final VisitorService visitorService;

    public VisitorController(VisitorService visitorService) {
        this.visitorService = visitorService;
    }

    @PostMapping
    public Result<VisitorResponse> register(@Valid @RequestBody VisitorRegisterRequest request) {
        return Result.ok(visitorService.register(request));
    }

    @GetMapping("/{id}")
    public Result<VisitorResponse> getById(@PathVariable Long id) {
        return Result.ok(visitorService.getById(id));
    }

    @GetMapping
    public Result<PageResult<VisitorResponse>> list(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return Result.ok(visitorService.list(page, size));
    }

    @PutMapping("/{id}")
    public Result<VisitorResponse> update(@PathVariable Long id,
                                           @Valid @RequestBody VisitorRegisterRequest request) {
        return Result.ok(visitorService.update(id, request));
    }

    @GetMapping("/search")
    public Result<PageResult<VisitorResponse>> search(@RequestParam String keyword,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size) {
        return Result.ok(visitorService.search(keyword, page, size));
    }
}

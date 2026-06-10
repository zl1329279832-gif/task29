package com.visitor.controller;

import com.visitor.common.result.PageResult;
import com.visitor.common.result.Result;
import com.visitor.dto.request.AbnormalPassRequest;
import com.visitor.dto.response.AbnormalPassResponse;
import com.visitor.service.AbnormalPassService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/abnormal-pass")
public class AbnormalPassController {

    private final AbnormalPassService abnormalPassService;

    public AbnormalPassController(AbnormalPassService abnormalPassService) {
        this.abnormalPassService = abnormalPassService;
    }

    @PostMapping
    public Result<AbnormalPassResponse> record(@Valid @RequestBody AbnormalPassRequest request) {
        return Result.ok(abnormalPassService.record(request));
    }

    @GetMapping
    public Result<PageResult<AbnormalPassResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer handled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(abnormalPassService.list(type, handled, page, size));
    }

    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        abnormalPassService.handle(id, body.get("handleRemark"));
        return Result.ok();
    }
}

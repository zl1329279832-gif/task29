package com.visitor.controller;

import com.visitor.common.result.Result;
import com.visitor.dto.response.PassCodeResponse;
import com.visitor.service.PassCodeService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/pass-codes")
public class PassCodeController {

    private final PassCodeService passCodeService;

    public PassCodeController(PassCodeService passCodeService) {
        this.passCodeService = passCodeService;
    }

    @GetMapping("/appointment/{appointmentId}")
    public Result<PassCodeResponse> getByAppointmentId(@PathVariable Long appointmentId) {
        return Result.ok(passCodeService.getByAppointmentId(appointmentId));
    }

    @PostMapping("/verify")
    public Result<PassCodeResponse> verify(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        return Result.ok(passCodeService.verify(code));
    }

    @PostMapping("/{id}/revoke")
    public Result<Void> revoke(@PathVariable Long id) {
        passCodeService.revoke(id);
        return Result.ok();
    }
}

package com.visitor.controller;

import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.PassCodeVO;
import com.visitor.service.PassCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pass-codes")
@RequiredArgsConstructor
public class PassCodeController {

    private final PassCodeService passCodeService;

    @GetMapping("/{appointmentId}")
    public ApiResponse<PassCodeVO> getPassCode(@PathVariable Long appointmentId) {
        return ApiResponse.success(passCodeService.getPassCodeVO(appointmentId));
    }

    @PostMapping("/verify")
    public ApiResponse<Void> verify(@RequestParam String code) {
        passCodeService.verifyAndUse(code, "VERIFY");
        return ApiResponse.success();
    }
}

package com.visitor.controller;

import com.visitor.common.result.Result;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.LoginRequest;
import com.visitor.dto.response.LoginResponse;
import com.visitor.entity.SysUser;
import com.visitor.mapper.SysUserMapper;
import com.visitor.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final SysUserMapper sysUserMapper;

    public AuthController(AuthService authService, SysUserMapper sysUserMapper) {
        this.authService = authService;
        this.sysUserMapper = sysUserMapper;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        authService.logout(token);
        return Result.ok();
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Long userId = SecurityUtils.getCurrentUserId();
        SysUser user = sysUserMapper.findById(userId);
        return Result.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "realName", user.getRealName(),
                "department", user.getDepartment() != null ? user.getDepartment() : "",
                "roles", SecurityUtils.getCurrentRoles()
        ));
    }
}

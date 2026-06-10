package com.visitor.service.impl;

import com.visitor.common.constant.RedisKeyConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.util.JwtUtils;
import com.visitor.dto.request.LoginRequest;
import com.visitor.dto.response.LoginResponse;
import com.visitor.mapper.SysUserMapper;
import com.visitor.security.CustomUserDetails;
import com.visitor.service.AuthService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;

    public AuthServiceImpl(AuthenticationManager authenticationManager, JwtUtils jwtUtils,
                           StringRedisTemplate redisTemplate) {
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.toList());

        String token = jwtUtils.generateToken(userDetails.getUserId(), userDetails.getUsername(), roles);

        return LoginResponse.builder()
                .token(token)
                .userId(userDetails.getUserId())
                .username(userDetails.getUsername())
                .realName(userDetails.getRealName())
                .roles(roles)
                .build();
    }

    @Override
    public void logout(String token) {
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token != null && jwtUtils.validateToken(token)) {
            long expiration = jwtUtils.getExpirationFromToken(token) - System.currentTimeMillis();
            if (expiration > 0) {
                redisTemplate.opsForValue().set(
                        RedisKeyConstants.JWT_BLACKLIST_PREFIX + token, "1", expiration, TimeUnit.MILLISECONDS);
            }
        }
    }
}

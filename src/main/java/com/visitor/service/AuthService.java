package com.visitor.service;

import com.visitor.dto.request.LoginRequest;
import com.visitor.dto.response.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);
    void logout(String token);
}

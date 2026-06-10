package com.visitor.controller;

import com.visitor.common.util.JwtUtils;
import com.visitor.dto.response.PassCodeResponse;
import com.visitor.security.UserDetailsServiceImpl;
import com.visitor.service.PassCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PassCodeController.class)
class PassCodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PassCodeService passCodeService;

    @MockBean private JwtUtils jwtUtils;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean private UserDetailsServiceImpl userDetailsServiceImpl;

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void getByAppointmentId_success() throws Exception {
        PassCodeResponse response = new PassCodeResponse();
        response.setId(1L);
        response.setCode("test-code");
        response.setStatus("ACTIVE");
        when(passCodeService.getByAppointmentId(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/pass-codes/appointment/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("test-code"));
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void verify_success() throws Exception {
        PassCodeResponse response = new PassCodeResponse();
        response.setId(1L);
        response.setStatus("ACTIVE");
        when(passCodeService.verify("test-code")).thenReturn(response);

        mockMvc.perform(post("/api/v1/pass-codes/verify")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"test-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void revoke_success() throws Exception {
        mockMvc.perform(post("/api/v1/pass-codes/1/revoke")
                        .with(csrf()))
                .andExpect(status().isOk());
    }
}

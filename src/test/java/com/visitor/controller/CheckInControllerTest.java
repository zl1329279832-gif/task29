package com.visitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.common.result.PageResult;
import com.visitor.dto.request.CheckInRequest;
import com.visitor.dto.response.CheckInResponse;
import com.visitor.service.CheckInService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import com.visitor.common.util.JwtUtils;
import com.visitor.security.UserDetailsServiceImpl;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CheckInController.class)
@Import(CheckInControllerTest.MethodSecurityConfig.class)
class CheckInControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CheckInService checkInService;

    @MockBean private JwtUtils jwtUtils;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean private UserDetailsServiceImpl userDetailsServiceImpl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @WithMockUser(roles = "SECURITY")
    void checkIn_success() throws Exception {
        CheckInRequest request = new CheckInRequest();
        request.setPassCode("test-code");
        request.setGateId("GATE-01");

        CheckInResponse response = new CheckInResponse();
        response.setId(1L);
        response.setStatus("CHECKED_IN");
        when(checkInService.checkIn(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/check-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHECKED_IN"));
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void checkIn_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/check-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passCode\":\"code\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void getCurrentVisitors_success() throws Exception {
        PageResult<CheckInResponse> pageResult = PageResult.of(List.of(), 0L, 1, 10);
        when(checkInService.getCurrentVisitors(anyInt(), anyInt())).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/check-in/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

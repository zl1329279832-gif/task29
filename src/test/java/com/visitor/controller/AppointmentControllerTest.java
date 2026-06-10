package com.visitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.visitor.common.result.PageResult;
import com.visitor.dto.request.AppointmentCreateRequest;
import com.visitor.dto.response.AppointmentResponse;
import com.visitor.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import com.visitor.common.util.JwtUtils;
import com.visitor.security.UserDetailsServiceImpl;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AppointmentController.class)
class AppointmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppointmentService appointmentService;

    @MockBean private JwtUtils jwtUtils;
    @MockBean private StringRedisTemplate stringRedisTemplate;
    @MockBean private UserDetailsServiceImpl userDetailsServiceImpl;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void create_success() throws Exception {
        AppointmentCreateRequest request = new AppointmentCreateRequest();
        request.setVisitorName("张三");
        request.setVisitorPhone("13800000001");
        request.setVisitReason("商务洽谈");
        request.setVisitStartTime(LocalDateTime.now().plusHours(1));
        request.setVisitEndTime(LocalDateTime.now().plusHours(3));

        AppointmentResponse response = new AppointmentResponse();
        response.setId(1L);
        response.setAppointmentNo("VIS001");
        response.setStatus("PENDING_APPROVAL");
        when(appointmentService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/appointments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.appointmentNo").value("VIS001"));
    }

    @Test
    void create_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/appointments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void list_success() throws Exception {
        PageResult<AppointmentResponse> pageResult = PageResult.of(List.of(), 0L, 1, 10);
        when(appointmentService.list(any(), anyInt(), anyInt())).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/appointments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}

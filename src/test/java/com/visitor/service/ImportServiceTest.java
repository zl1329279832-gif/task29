package com.visitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.ImportBatchMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.MeetingVisitorImportItem;
import com.visitor.model.dto.MeetingVisitorImportRequest;
import com.visitor.model.entity.ImportBatch;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.ImportStatusEnum;
import com.visitor.model.enums.RoleEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImportServiceTest {

    @InjectMocks
    private ImportService importService;

    @Mock private ImportBatchMapper importBatchMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private VisitorService visitorService;
    @Mock private AppointmentService appointmentService;
    @Mock private BlacklistService blacklistService;
    @Mock private AppointmentMapper appointmentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    private SysUser operator;

    @BeforeEach
    void setUp() {
        operator = SysUser.builder()
                .id(1L).username("employee1").realName("Zhang San")
                .role(RoleEnum.EMPLOYEE).status(1).build();

        var auth = new UsernamePasswordAuthenticationToken(
                "employee1", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testImportAllSuccess() {
        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);

        MeetingVisitorImportItem item = new MeetingVisitorImportItem();
        item.setName("Visitor One");
        item.setPhone("13800000001");
        item.setExpectedArrive(LocalDateTime.now().plusDays(1));
        request.setVisitors(List.of(item));

        Visitor visitor = Visitor.builder().id(10L).name("Visitor One").build();

        when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
        when(sysUserMapper.selectById(1L)).thenReturn(operator);
        when(visitorService.registerOrFind(any())).thenReturn(visitor);
        when(blacklistService.check(any(), any(), any())).thenReturn(null);
        when(appointmentMapper.insert(any())).thenReturn(1);
        when(importBatchMapper.insert(any())).thenReturn(1);

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(ImportStatusEnum.COMPLETED, result.getStatus());
    }

    @Test
    void testImportPartialFailure() {
        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);

        MeetingVisitorImportItem goodItem = new MeetingVisitorImportItem();
        goodItem.setName("Good Visitor");
        goodItem.setPhone("13800000001");
        goodItem.setExpectedArrive(LocalDateTime.now().plusDays(1));

        MeetingVisitorImportItem badItem = new MeetingVisitorImportItem();
        badItem.setName(""); // Empty name will fail validation
        badItem.setExpectedArrive(LocalDateTime.now().plusDays(1));

        request.setVisitors(List.of(goodItem, badItem));

        Visitor visitor = Visitor.builder().id(10L).name("Good Visitor").build();

        when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
        when(sysUserMapper.selectById(1L)).thenReturn(operator);
        when(visitorService.registerOrFind(any())).thenReturn(visitor);
        when(blacklistService.check(any(), any(), any())).thenReturn(null);
        when(appointmentMapper.insert(any())).thenReturn(1);
        when(importBatchMapper.insert(any())).thenReturn(1);

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(2, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ImportStatusEnum.PARTIAL_FAIL, result.getStatus());
        assertNotNull(result.getFailDetail());
        assertTrue(result.getFailDetail().contains("row"));
    }

    @Test
    void testImportAllFailure() {
        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);

        MeetingVisitorImportItem badItem = new MeetingVisitorImportItem();
        badItem.setName(""); // Empty name
        badItem.setExpectedArrive(null); // Null arrival
        request.setVisitors(List.of(badItem));

        when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
        when(importBatchMapper.insert(any())).thenReturn(1);

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ImportStatusEnum.FAILED, result.getStatus());
    }

    @Test
    void testImportBlacklistedVisitor() {
        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);

        MeetingVisitorImportItem item = new MeetingVisitorImportItem();
        item.setName("Blacklisted Person");
        item.setPhone("13800000001");
        item.setExpectedArrive(LocalDateTime.now().plusDays(1));
        request.setVisitors(List.of(item));

        Visitor visitor = Visitor.builder().id(10L).name("Blacklisted Person").build();
        com.visitor.model.entity.Blacklist bl = com.visitor.model.entity.Blacklist.builder()
                .id(1L).reason("Security threat").build();

        when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);
        when(visitorService.registerOrFind(any())).thenReturn(visitor);
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(bl);
        when(importBatchMapper.insert(any())).thenReturn(1);

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ImportStatusEnum.FAILED, result.getStatus());
    }

    @Test
    void testImportEmptyData() {
        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setVisitors(List.of());

        when(sysUserMapper.findByUsername("employee1")).thenReturn(operator);

        BizException ex = assertThrows(BizException.class,
                () -> importService.importMeetingVisitors(request));
        assertEquals(ErrorCode.IMPORT_DATA_EMPTY, ex.getErrorCode());
    }
}

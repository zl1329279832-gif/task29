package com.visitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.ImportBatchMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.MeetingVisitorImportItem;
import com.visitor.model.dto.MeetingVisitorImportRequest;
import com.visitor.model.entity.Blacklist;
import com.visitor.model.entity.ImportBatch;
import com.visitor.model.entity.SysUser;
import com.visitor.model.entity.Visitor;
import com.visitor.model.enums.ImportStatusEnum;
import com.visitor.util.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended ImportService tests focusing on meeting room area authorization support.
 */
@ExtendWith(MockitoExtension.class)
class ImportServiceExtTest {

    @InjectMocks
    private ImportService importService;

    @Mock private AppointmentMapper appointmentMapper;
    @Mock private ImportBatchMapper importBatchMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private VisitorService visitorService;
    @Mock private AppointmentService appointmentService;
    @Mock private BlacklistService blacklistService;
    @Mock private AreaAuthorizationService areaAuthorizationService;
    @Mock private MeetingRoomService meetingRoomService;
    @Mock private RedisLock redisLock;
    @Mock private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var auth = new UsernamePasswordAuthenticationToken("admin", null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── Helper methods ─────────────────────────────────────────────────

    private MeetingVisitorImportItem createValidItem(String name, String phone) {
        MeetingVisitorImportItem item = new MeetingVisitorImportItem();
        item.setName(name);
        item.setPhone(phone);
        item.setCompany("Test Corp");
        item.setPurpose("Meeting");
        item.setExpectedArrive(LocalDateTime.now().plusDays(1));
        item.setExpectedLeave(LocalDateTime.now().plusDays(1).plusHours(2));
        return item;
    }

    private void setupCommonMocks() throws Exception {
        SysUser admin = SysUser.builder().id(1L).username("admin").build();
        when(sysUserMapper.findByUsername("admin")).thenReturn(admin);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(importBatchMapper.insert(any())).thenReturn(1);
        when(appointmentMapper.insert(any())).thenReturn(1);
        when(sysUserMapper.selectById(anyLong())).thenReturn(admin);

        Visitor visitor = Visitor.builder().id(10L).name("Test").phone("138").build();
        when(visitorService.registerOrFind(any())).thenReturn(visitor);
        when(blacklistService.check(any(), any(), any())).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");
    }

    // ── Test 1: With meeting room, area authorizations created ─────────

    @Test
    void testImport_WithMeetingRoom_AreaAuthsCreated() throws Exception {
        setupCommonMocks();

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(1L);
        request.setVisitors(List.of(
                createValidItem("Visitor A", "13800000001"),
                createValidItem("Visitor B", "13800000002")
        ));

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(ImportStatusEnum.COMPLETED, result.getStatus());
        verify(areaAuthorizationService, times(2)).batchGrantForMeetingRoom(
                any(), eq(10L), eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), eq(1L));
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Test 2: Without meeting room, no area authorizations ───────────

    @Test
    void testImport_WithoutMeetingRoom_NoAreaAuths() throws Exception {
        setupCommonMocks();

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(null);
        request.setVisitors(List.of(
                createValidItem("Visitor C", "13800000003")
        ));

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(ImportStatusEnum.COMPLETED, result.getStatus());
        verify(areaAuthorizationService, never()).batchGrantForMeetingRoom(
                any(), anyLong(), anyLong(), any(), any(), anyLong());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Test 3: Area auth failure is non-fatal ─────────────────────────

    @Test
    void testImport_MeetingRoom_AreaAuthFailure_StillSucceeds() throws Exception {
        setupCommonMocks();

        when(areaAuthorizationService.batchGrantForMeetingRoom(
                any(), anyLong(), eq(1L), any(), any(), anyLong()))
                .thenThrow(new RuntimeException("Area service unavailable"));

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(1L);
        request.setVisitors(List.of(
                createValidItem("Visitor D", "13800000004")
        ));

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(ImportStatusEnum.COMPLETED, result.getStatus());
        verify(appointmentMapper, times(1)).insert(any());
        verify(areaAuthorizationService, times(1)).batchGrantForMeetingRoom(
                any(), eq(10L), eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), eq(1L));
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Test 4: Blacklisted visitor gets no area auth ──────────────────

    @Test
    void testImport_BlacklistedVisitor_NoAreaAuth() throws Exception {
        SysUser admin = SysUser.builder().id(1L).username("admin").build();
        when(sysUserMapper.findByUsername("admin")).thenReturn(admin);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn("lock-value");
        when(importBatchMapper.insert(any())).thenReturn(1);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        Visitor visitor = Visitor.builder().id(10L).name("Bad Actor").phone("13800000005").build();
        when(visitorService.registerOrFind(any())).thenReturn(visitor);

        Blacklist bl = Blacklist.builder().id(1L).reason("Security threat").build();
        when(blacklistService.check(anyString(), any(), anyString())).thenReturn(bl);

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(1L);
        request.setVisitors(List.of(
                createValidItem("Bad Actor", "13800000005")
        ));

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(1, result.getTotalCount());
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ImportStatusEnum.FAILED, result.getStatus());
        verify(areaAuthorizationService, never()).batchGrantForMeetingRoom(
                any(), anyLong(), anyLong(), any(), any(), anyLong());
        verify(appointmentMapper, never()).insert(any());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Test 5: Partial failure, area auth only for successful visitor ─

    @Test
    void testImport_WithMeetingRoom_PartialFailure() throws Exception {
        setupCommonMocks();

        MeetingVisitorImportItem goodItem = createValidItem("Good Visitor", "13800000006");

        MeetingVisitorImportItem badItem = new MeetingVisitorImportItem();
        badItem.setName("");  // blank name triggers validation failure
        badItem.setPhone("13800000007");
        badItem.setExpectedArrive(LocalDateTime.now().plusDays(1));

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(1L);
        request.setVisitors(List.of(goodItem, badItem));

        ImportBatch result = importService.importMeetingVisitors(request);

        assertEquals(2, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(ImportStatusEnum.PARTIAL_FAIL, result.getStatus());
        verify(areaAuthorizationService, times(1)).batchGrantForMeetingRoom(
                any(), eq(10L), eq(1L), any(LocalDateTime.class), any(LocalDateTime.class), eq(1L));
        verify(appointmentMapper, times(1)).insert(any());
        verify(redisLock).unlock(anyString(), eq("lock-value"));
    }

    // ── Test 6: Lock acquisition failure ───────────────────────────────

    @Test
    void testImport_LockFailed() {
        SysUser admin = SysUser.builder().id(1L).username("admin").build();
        when(sysUserMapper.findByUsername("admin")).thenReturn(admin);
        when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(null);

        MeetingVisitorImportRequest request = new MeetingVisitorImportRequest();
        request.setHostId(1L);
        request.setMeetingRoomId(1L);
        request.setVisitors(List.of(
                createValidItem("Visitor E", "13800000008")
        ));

        BizException ex = assertThrows(BizException.class,
                () -> importService.importMeetingVisitors(request));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(importBatchMapper, never()).insert(any());
        verify(appointmentMapper, never()).insert(any());
        verify(areaAuthorizationService, never()).batchGrantForMeetingRoom(
                any(), anyLong(), anyLong(), any(), any(), anyLong());
    }
}

package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaAuthorizationMapper;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.AreaAuthorization;
import com.visitor.model.entity.MeetingRoom;
import com.visitor.model.enums.AreaAuthStatusEnum;
import com.visitor.model.enums.AreaStatusEnum;
import com.visitor.model.enums.SecurityLevelEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AreaAuthorizationServiceTest {

    @InjectMocks
    private AreaAuthorizationService areaAuthorizationService;

    @Mock
    private AreaAuthorizationMapper areaAuthorizationMapper;

    @Mock
    private AreaService areaService;

    @Mock
    private MeetingRoomService meetingRoomService;

    private final LocalDateTime validFrom = LocalDateTime.of(2026, 6, 10, 9, 0);
    private final LocalDateTime validTo = LocalDateTime.of(2026, 6, 10, 18, 0);

    @Test
    void testGrantAuthorization_Success() {
        Area area = Area.builder()
                .id(1L).areaCode("AREA-001").areaName("Building A")
                .securityLevel(SecurityLevelEnum.LOW).status(AreaStatusEnum.ACTIVE)
                .parentAreaId(null).build();

        when(areaService.getById(1L)).thenReturn(area);
        when(areaAuthorizationMapper.selectActiveAuth(1L, 1L)).thenReturn(null);
        when(areaAuthorizationMapper.insert(any(AreaAuthorization.class))).thenReturn(1);

        AreaAuthorization result = areaAuthorizationService.grantAuthorization(
                1L, 10L, 1L, validFrom, validTo, 100L);

        assertNotNull(result);
        assertEquals(1L, result.getAppointmentId());
        assertEquals(10L, result.getVisitorId());
        assertEquals(1L, result.getAreaId());
        assertEquals(AreaAuthStatusEnum.ACTIVE, result.getStatus());
        assertEquals(100L, result.getGrantedBy());
        verify(areaAuthorizationMapper).insert(any(AreaAuthorization.class));
    }

    @Test
    void testGrantAuthorization_Idempotent() {
        Area area = Area.builder()
                .id(1L).areaCode("AREA-001").areaName("Building A")
                .securityLevel(SecurityLevelEnum.LOW).status(AreaStatusEnum.ACTIVE)
                .parentAreaId(null).build();

        AreaAuthorization existing = AreaAuthorization.builder()
                .id(99L).appointmentId(1L).visitorId(10L).areaId(1L)
                .validFrom(validFrom).validTo(validTo).grantedBy(100L)
                .status(AreaAuthStatusEnum.ACTIVE).build();

        when(areaService.getById(1L)).thenReturn(area);
        when(areaAuthorizationMapper.selectActiveAuth(1L, 1L)).thenReturn(existing);

        AreaAuthorization result = areaAuthorizationService.grantAuthorization(
                1L, 10L, 1L, validFrom, validTo, 100L);

        assertSame(existing, result);
        assertEquals(99L, result.getId());
        verify(areaAuthorizationMapper, never()).insert(any());
    }

    @Test
    void testCheckAuthorization_Authorized() {
        AreaAuthorization auth = AreaAuthorization.builder()
                .id(1L).appointmentId(1L).areaId(1L)
                .status(AreaAuthStatusEnum.ACTIVE).build();

        when(areaAuthorizationMapper.selectActiveAuth(1L, 1L)).thenReturn(auth);

        boolean result = areaAuthorizationService.checkAuthorization(1L, 1L);

        assertTrue(result);
    }

    @Test
    void testCheckAuthorization_Unauthorized() {
        when(areaAuthorizationMapper.selectActiveAuth(1L, 1L)).thenReturn(null);

        boolean result = areaAuthorizationService.checkAuthorization(1L, 1L);

        assertFalse(result);
    }

    @Test
    void testRevokeByAppointment_AllRevoked() {
        AreaAuthorization auth1 = AreaAuthorization.builder()
                .id(1L).appointmentId(1L).visitorId(10L).areaId(1L)
                .status(AreaAuthStatusEnum.ACTIVE).build();
        AreaAuthorization auth2 = AreaAuthorization.builder()
                .id(2L).appointmentId(1L).visitorId(10L).areaId(2L)
                .status(AreaAuthStatusEnum.ACTIVE).build();
        AreaAuthorization auth3 = AreaAuthorization.builder()
                .id(3L).appointmentId(1L).visitorId(10L).areaId(3L)
                .status(AreaAuthStatusEnum.EXPIRED).build();

        when(areaAuthorizationMapper.selectByAppointment(1L))
                .thenReturn(Arrays.asList(auth1, auth2, auth3));

        areaAuthorizationService.revokeByAppointment(1L);

        assertEquals(AreaAuthStatusEnum.REVOKED, auth1.getStatus());
        assertEquals(AreaAuthStatusEnum.REVOKED, auth2.getStatus());
        assertEquals(AreaAuthStatusEnum.EXPIRED, auth3.getStatus());
        verify(areaAuthorizationMapper).updateById(auth1);
        verify(areaAuthorizationMapper).updateById(auth2);
        verify(areaAuthorizationMapper, never()).updateById(auth3);
    }

    @Test
    void testBatchGrantForMeetingRoom_GrantsRoomAndParentAreas() {
        MeetingRoom room = MeetingRoom.builder()
                .id(1L).roomCode("ROOM-001").roomName("Meeting Room A")
                .areaId(2L).status(AreaStatusEnum.ACTIVE).build();

        Area childArea = Area.builder()
                .id(2L).areaCode("AREA-002").areaName("Floor 3")
                .securityLevel(SecurityLevelEnum.MEDIUM).status(AreaStatusEnum.ACTIVE)
                .parentAreaId(1L).build();

        Area parentArea = Area.builder()
                .id(1L).areaCode("AREA-001").areaName("Building A")
                .securityLevel(SecurityLevelEnum.LOW).status(AreaStatusEnum.ACTIVE)
                .parentAreaId(null).build();

        when(meetingRoomService.getById(1L)).thenReturn(room);
        when(areaService.getById(2L)).thenReturn(childArea);
        when(areaService.getById(1L)).thenReturn(parentArea);
        when(areaAuthorizationMapper.selectActiveAuth(anyLong(), anyLong())).thenReturn(null);
        when(areaAuthorizationMapper.insert(any(AreaAuthorization.class))).thenReturn(1);

        List<AreaAuthorization> results = areaAuthorizationService.batchGrantForMeetingRoom(
                1L, 10L, 1L, validFrom, validTo, 100L);

        assertEquals(2, results.size());
        assertEquals(2L, results.get(0).getAreaId());
        assertEquals(1L, results.get(1).getAreaId());
        verify(areaAuthorizationMapper, times(2)).insert(any(AreaAuthorization.class));
    }

    @Test
    void testExpireAuthorizations_DelegatesToMapper() {
        when(areaAuthorizationMapper.expireAuthorizations(any(LocalDateTime.class))).thenReturn(5);

        int result = areaAuthorizationService.expireAuthorizations();

        assertEquals(5, result);
        verify(areaAuthorizationMapper).expireAuthorizations(any(LocalDateTime.class));
    }

    @Test
    void testRevokeById_NotFound() {
        when(areaAuthorizationMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> areaAuthorizationService.revokeById(999L));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        verify(areaAuthorizationMapper, never()).updateById(any());
    }
}

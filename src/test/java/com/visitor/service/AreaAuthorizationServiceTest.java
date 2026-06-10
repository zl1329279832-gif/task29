package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaAuthorizationMapper;
import com.visitor.mapper.GateMapper;
import com.visitor.model.entity.AreaAuthorization;
import com.visitor.model.entity.Gate;
import com.visitor.model.enums.GateTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AreaAuthorizationServiceTest {

    @InjectMocks
    private AreaAuthorizationService areaAuthorizationService;

    @Mock private AreaAuthorizationMapper areaAuthorizationMapper;
    @Mock private GateMapper gateMapper;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now();
    }

    @Test
    void testCreateAuthorization_Success() {
        when(areaAuthorizationMapper.insert(any(AreaAuthorization.class))).thenReturn(1);

        AreaAuthorization result = areaAuthorizationService.createAuthorization(
                1L, 1L, now, now.plusHours(8));

        assertNotNull(result);
        assertEquals(1L, result.getAppointmentId());
        assertEquals(1L, result.getAreaId());
        verify(areaAuthorizationMapper).insert(any(AreaAuthorization.class));
    }

    @Test
    void testCreateBatchAuthorizations() {
        when(areaAuthorizationMapper.insert(any(AreaAuthorization.class))).thenReturn(1);

        areaAuthorizationService.createBatchAuthorizations(
                1L, List.of(1L, 2L, 3L), now, now.plusHours(8));

        verify(areaAuthorizationMapper, times(3)).insert(any(AreaAuthorization.class));
    }

    @Test
    void testIsAreaAccessAllowed_Authorized() {
        when(areaAuthorizationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertTrue(areaAuthorizationService.isAreaAccessAllowed(1L, 1L, now));
    }

    @Test
    void testIsAreaAccessAllowed_NotAuthorized() {
        when(areaAuthorizationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        assertFalse(areaAuthorizationService.isAreaAccessAllowed(1L, 99L, now));
    }

    @Test
    void testValidateGateAccess_Success() {
        Gate gate = Gate.builder()
                .id(1L).areaId(1L).gateType(GateTypeEnum.NORMAL).status(1).build();
        when(gateMapper.selectById(1L)).thenReturn(gate);
        when(areaAuthorizationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        assertDoesNotThrow(() ->
                areaAuthorizationService.validateGateAccess(1L, 1L, now));
    }

    @Test
    void testValidateGateAccess_Unauthorized() {
        Gate gate = Gate.builder()
                .id(1L).areaId(1L).gateType(GateTypeEnum.NORMAL).status(1).build();
        when(gateMapper.selectById(1L)).thenReturn(gate);
        when(areaAuthorizationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        BizException ex = assertThrows(BizException.class,
                () -> areaAuthorizationService.validateGateAccess(1L, 1L, now));
        assertEquals(ErrorCode.UNAUTHORIZED_AREA_ACCESS, ex.getErrorCode());
    }

    @Test
    void testValidateGateAccess_GateNotFound() {
        when(gateMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> areaAuthorizationService.validateGateAccess(1L, 999L, now));
        assertEquals(ErrorCode.GATE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testRevokeByAppointmentId() {
        when(areaAuthorizationMapper.deleteByAppointmentId(1L)).thenReturn(3);

        areaAuthorizationService.revokeByAppointmentId(1L);

        verify(areaAuthorizationMapper).deleteByAppointmentId(1L);
    }

    @Test
    void testGetAuthorizedAreas() {
        AreaAuthorization auth1 = AreaAuthorization.builder()
                .id(1L).appointmentId(1L).areaId(1L).build();
        AreaAuthorization auth2 = AreaAuthorization.builder()
                .id(2L).appointmentId(1L).areaId(2L).build();

        when(areaAuthorizationMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(auth1, auth2));

        List<AreaAuthorization> result = areaAuthorizationService.getAuthorizedAreas(1L);

        assertEquals(2, result.size());
    }

    @Test
    void testCountAuthorizedAreas() {
        when(areaAuthorizationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);

        int count = areaAuthorizationService.countAuthorizedAreas(1L);

        assertEquals(3, count);
    }
}

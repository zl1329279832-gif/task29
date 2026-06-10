package com.visitor.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaMapper;
import com.visitor.mapper.GateMapper;
import com.visitor.model.dto.AreaCreateRequest;
import com.visitor.model.dto.GateCreateRequest;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.Gate;
import com.visitor.model.enums.GateTypeEnum;
import com.visitor.model.vo.GateVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AreaServiceTest {

    @InjectMocks
    private AreaService areaService;

    @Mock private AreaMapper areaMapper;
    @Mock private GateMapper gateMapper;

    private Area testArea;
    private Gate testGate;

    @BeforeEach
    void setUp() {
        testArea = Area.builder()
                .id(1L).name("A栋办公区").building("A栋").status(1).build();
        testGate = Gate.builder()
                .id(1L).name("A栋正门").areaId(1L).gateType(GateTypeEnum.NORMAL).status(1).build();
    }

    // ── Area CRUD ─────────────────────────────────────────────────────

    @Test
    void testCreateArea_Success() {
        AreaCreateRequest request = new AreaCreateRequest();
        request.setName("B栋会议区");
        request.setBuilding("B栋");

        when(areaMapper.insert(any(Area.class))).thenAnswer(invocation -> {
            Area area = invocation.getArgument(0);
            area.setId(2L);
            return 1;
        });

        Area result = areaService.createArea(request);

        assertNotNull(result);
        assertEquals("B栋会议区", result.getName());
        assertEquals("B栋", result.getBuilding());
        assertEquals(1, result.getStatus());
        verify(areaMapper).insert(any(Area.class));
    }

    @Test
    void testGetAreaDetail_IncludesGates() {
        when(areaMapper.selectById(1L)).thenReturn(testArea);

        GateVO gateVO = GateVO.builder()
                .id(1L).name("A栋正门").areaId(1L).areaName("A栋办公区").build();
        when(gateMapper.selectGateListByAreaId(1L)).thenReturn(List.of(gateVO));

        var result = areaService.getAreaDetail(1L);

        assertNotNull(result);
        assertEquals("A栋办公区", result.getName());
        assertEquals(1, result.getGates().size());
        assertEquals("A栋正门", result.getGates().get(0).getName());
    }

    @Test
    void testGetAreaDetail_NotFound() {
        when(areaMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> areaService.getAreaDetail(999L));
        assertEquals(ErrorCode.AREA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testDisableArea_Success() {
        when(areaMapper.selectById(1L)).thenReturn(testArea);
        when(areaMapper.updateById(any())).thenReturn(1);

        areaService.disableArea(1L);

        verify(areaMapper).updateById(argThat(area -> area.getStatus() == 0));
    }

    // ── Gate CRUD ─────────────────────────────────────────────────────

    @Test
    void testCreateGate_Success() {
        GateCreateRequest request = new GateCreateRequest();
        request.setName("B栋侧门");
        request.setAreaId(1L);
        request.setGateType(GateTypeEnum.ENTRY_ONLY);

        when(areaMapper.selectById(1L)).thenReturn(testArea);
        when(gateMapper.insert(any(Gate.class))).thenReturn(1);

        Gate result = areaService.createGate(request);

        assertNotNull(result);
        assertEquals("B栋侧门", result.getName());
        assertEquals(GateTypeEnum.ENTRY_ONLY, result.getGateType());
    }

    @Test
    void testCreateGate_AreaNotFound() {
        GateCreateRequest request = new GateCreateRequest();
        request.setName("X门");
        request.setAreaId(999L);

        when(areaMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> areaService.createGate(request));
        assertEquals(ErrorCode.AREA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testCreateGate_AreaDisabled() {
        testArea.setStatus(0);

        GateCreateRequest request = new GateCreateRequest();
        request.setName("X门");
        request.setAreaId(1L);

        when(areaMapper.selectById(1L)).thenReturn(testArea);

        BizException ex = assertThrows(BizException.class,
                () -> areaService.createGate(request));
        assertEquals(ErrorCode.AREA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testDisableGate_Success() {
        when(gateMapper.selectById(1L)).thenReturn(testGate);
        when(gateMapper.updateById(any())).thenReturn(1);

        areaService.disableGate(1L);

        verify(gateMapper).updateById(argThat(gate -> gate.getStatus() == 0));
    }

    // ── Validation helpers ────────────────────────────────────────────

    @Test
    void testGetGateAndValidate_Success() {
        when(gateMapper.selectById(1L)).thenReturn(testGate);

        Gate result = areaService.getGateAndValidate(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void testGetGateAndValidate_NotFound() {
        when(gateMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> areaService.getGateAndValidate(999L));
        assertEquals(ErrorCode.GATE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testGetGateAndValidate_Disabled() {
        testGate.setStatus(0);
        when(gateMapper.selectById(1L)).thenReturn(testGate);

        BizException ex = assertThrows(BizException.class,
                () -> areaService.getGateAndValidate(1L));
        assertEquals(ErrorCode.GATE_DISABLED, ex.getErrorCode());
    }

    @Test
    void testIsGateEntryAllowed_Normal() {
        assertTrue(areaService.isGateEntryAllowed(testGate));
    }

    @Test
    void testIsGateEntryAllowed_ExitOnly() {
        testGate.setGateType(GateTypeEnum.EXIT_ONLY);
        assertFalse(areaService.isGateEntryAllowed(testGate));
    }

    @Test
    void testIsGateExitAllowed_EntryOnly() {
        testGate.setGateType(GateTypeEnum.ENTRY_ONLY);
        assertFalse(areaService.isGateExitAllowed(testGate));
    }
}

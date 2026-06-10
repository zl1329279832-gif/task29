package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.visitor.model.vo.AreaVO;
import com.visitor.model.vo.GateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaMapper areaMapper;
    private final GateMapper gateMapper;

    // ── Area CRUD ──────────────────────────────────────────────────────

    public Area createArea(AreaCreateRequest request) {
        Area area = Area.builder()
                .name(request.getName())
                .building(request.getBuilding())
                .description(request.getDescription())
                .status(1)
                .build();
        areaMapper.insert(area);
        log.info("Created area: {} ({})", area.getName(), area.getId());
        return area;
    }

    public AreaVO getAreaDetail(Long areaId) {
        Area area = areaMapper.selectById(areaId);
        if (area == null) {
            throw new BizException(ErrorCode.AREA_NOT_FOUND);
        }
        List<GateVO> gates = gateMapper.selectGateListByAreaId(areaId);
        return AreaVO.builder()
                .id(area.getId())
                .name(area.getName())
                .building(area.getBuilding())
                .description(area.getDescription())
                .status(area.getStatus())
                .gates(gates)
                .build();
    }

    public Page<Area> listAreas(String keyword, int page, int size) {
        LambdaQueryWrapper<Area> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Area::getName, keyword)
                    .or().like(Area::getBuilding, keyword);
        }
        wrapper.orderByAsc(Area::getId);
        return areaMapper.selectPage(new Page<>(page, size), wrapper);
    }

    public void disableArea(Long areaId) {
        Area area = areaMapper.selectById(areaId);
        if (area == null) {
            throw new BizException(ErrorCode.AREA_NOT_FOUND);
        }
        area.setStatus(0);
        areaMapper.updateById(area);
        log.info("Disabled area: {} ({})", area.getName(), areaId);
    }

    // ── Gate CRUD ──────────────────────────────────────────────────────

    public Gate createGate(GateCreateRequest request) {
        Area area = areaMapper.selectById(request.getAreaId());
        if (area == null) {
            throw new BizException(ErrorCode.AREA_NOT_FOUND);
        }
        if (area.getStatus() != 1) {
            throw new BizException(ErrorCode.AREA_NOT_FOUND, "区域已停用");
        }

        Gate gate = Gate.builder()
                .name(request.getName())
                .areaId(request.getAreaId())
                .locationDesc(request.getLocationDesc())
                .gateType(request.getGateType() != null ? request.getGateType() : GateTypeEnum.NORMAL)
                .status(1)
                .build();
        gateMapper.insert(gate);
        log.info("Created gate: {} in area {} ({})", gate.getName(), area.getName(), gate.getId());
        return gate;
    }

    public GateVO getGateDetail(Long gateId) {
        List<GateVO> gates = gateMapper.selectGateListByAreaId(null);
        return gates.stream()
                .filter(g -> g.getId().equals(gateId))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.GATE_NOT_FOUND));
    }

    public List<GateVO> listGatesByArea(Long areaId) {
        return gateMapper.selectGateListByAreaId(areaId);
    }

    public void disableGate(Long gateId) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new BizException(ErrorCode.GATE_NOT_FOUND);
        }
        gate.setStatus(0);
        gateMapper.updateById(gate);
        log.info("Disabled gate: {} ({})", gate.getName(), gateId);
    }

    // ── Validation helpers ─────────────────────────────────────────────

    /**
     * Get gate and validate it exists and is enabled.
     */
    public Gate getGateAndValidate(Long gateId) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new BizException(ErrorCode.GATE_NOT_FOUND);
        }
        if (gate.getStatus() != 1) {
            throw new BizException(ErrorCode.GATE_DISABLED);
        }
        return gate;
    }

    public boolean isGateEntryAllowed(Gate gate) {
        return gate.getGateType() != GateTypeEnum.EXIT_ONLY;
    }

    public boolean isGateExitAllowed(Gate gate) {
        return gate.getGateType() != GateTypeEnum.ENTRY_ONLY;
    }
}

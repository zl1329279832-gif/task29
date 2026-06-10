package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.GateMapper;
import com.visitor.model.dto.GateCreateRequest;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.Gate;
import com.visitor.model.enums.GateDirectionEnum;
import com.visitor.model.enums.GateStatusEnum;
import com.visitor.model.vo.GateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GateManageService {

    private final GateMapper gateMapper;
    private final AreaService areaService;

    public Gate create(GateCreateRequest request) {
        Gate existing = gateMapper.findByGateCode(request.getGateCode());
        if (existing != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "门岗编码已存在: " + request.getGateCode());
        }

        // Validate area exists
        areaService.getById(request.getAreaId());

        Gate gate = Gate.builder()
                .gateCode(request.getGateCode())
                .gateName(request.getGateName())
                .areaId(request.getAreaId())
                .direction(request.getDirection() != null
                        ? request.getDirection() : GateDirectionEnum.BIDIRECTIONAL)
                .status(GateStatusEnum.ACTIVE)
                .build();
        gateMapper.insert(gate);
        log.info("Created gate: {} ({})", gate.getGateName(), gate.getGateCode());
        return gate;
    }

    public Gate getById(Long id) {
        Gate gate = gateMapper.selectById(id);
        if (gate == null) {
            throw new BizException(ErrorCode.GATE_NOT_FOUND);
        }
        return gate;
    }

    public Gate validateGateActive(Long gateId) {
        Gate gate = getById(gateId);
        if (gate.getStatus() != GateStatusEnum.ACTIVE) {
            throw new BizException(ErrorCode.GATE_INACTIVE,
                    "门岗 " + gate.getGateName() + " 当前状态: " + gate.getStatus());
        }
        return gate;
    }

    public GateVO getDetail(Long id) {
        Gate gate = getById(id);
        Area area = areaService.getById(gate.getAreaId());
        return GateVO.builder()
                .id(gate.getId())
                .gateCode(gate.getGateCode())
                .gateName(gate.getGateName())
                .areaId(gate.getAreaId())
                .areaName(area.getAreaName())
                .direction(gate.getDirection().name())
                .status(gate.getStatus().name())
                .createdAt(gate.getCreatedAt())
                .build();
    }

    public Page<GateVO> list(String keyword, int page, int size) {
        LambdaQueryWrapper<Gate> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Gate::getGateName, keyword)
                    .or().like(Gate::getGateCode, keyword));
        }
        wrapper.orderByAsc(Gate::getGateCode);

        List<Gate> all = gateMapper.selectList(wrapper);
        Page<GateVO> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, all.size());
        if (start < all.size()) {
            result.setRecords(all.subList(start, end).stream().map(g -> {
                String areaName = "";
                try {
                    Area area = areaService.getById(g.getAreaId());
                    areaName = area.getAreaName();
                } catch (Exception ignored) {}
                return GateVO.builder()
                        .id(g.getId())
                        .gateCode(g.getGateCode())
                        .gateName(g.getGateName())
                        .areaId(g.getAreaId())
                        .areaName(areaName)
                        .direction(g.getDirection().name())
                        .status(g.getStatus().name())
                        .createdAt(g.getCreatedAt())
                        .build();
            }).toList());
        }
        result.setTotal(all.size());
        return result;
    }

    public Gate update(Long id, GateCreateRequest request) {
        Gate gate = getById(id);
        gate.setGateName(request.getGateName());
        gate.setAreaId(request.getAreaId());
        if (request.getDirection() != null) {
            gate.setDirection(request.getDirection());
        }
        gateMapper.updateById(gate);
        return gate;
    }

    public List<Gate> getByAreaId(Long areaId) {
        return gateMapper.selectByAreaId(areaId);
    }
}

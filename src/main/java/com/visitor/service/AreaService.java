package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaMapper;
import com.visitor.model.dto.AreaCreateRequest;
import com.visitor.model.entity.Area;
import com.visitor.model.enums.AreaStatusEnum;
import com.visitor.model.enums.SecurityLevelEnum;
import com.visitor.model.vo.AreaVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaMapper areaMapper;

    public Area create(AreaCreateRequest request) {
        Area existing = areaMapper.findByAreaCode(request.getAreaCode());
        if (existing != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "区域编码已存在: " + request.getAreaCode());
        }

        if (request.getParentAreaId() != null) {
            Area parent = areaMapper.selectById(request.getParentAreaId());
            if (parent == null) {
                throw new BizException(ErrorCode.AREA_NOT_FOUND, "父区域不存在");
            }
        }

        Area area = Area.builder()
                .areaCode(request.getAreaCode())
                .areaName(request.getAreaName())
                .buildingName(request.getBuildingName())
                .floorInfo(request.getFloorInfo())
                .securityLevel(request.getSecurityLevel() != null
                        ? request.getSecurityLevel() : SecurityLevelEnum.LOW)
                .parentAreaId(request.getParentAreaId())
                .status(AreaStatusEnum.ACTIVE)
                .build();
        areaMapper.insert(area);
        log.info("Created area: {} ({})", area.getAreaName(), area.getAreaCode());
        return area;
    }

    public Area getById(Long id) {
        Area area = areaMapper.selectById(id);
        if (area == null) {
            throw new BizException(ErrorCode.AREA_NOT_FOUND);
        }
        return area;
    }

    public AreaVO getDetail(Long id) {
        Area area = getById(id);
        String parentName = null;
        if (area.getParentAreaId() != null) {
            Area parent = areaMapper.selectById(area.getParentAreaId());
            if (parent != null) {
                parentName = parent.getAreaName();
            }
        }
        return AreaVO.builder()
                .id(area.getId())
                .areaCode(area.getAreaCode())
                .areaName(area.getAreaName())
                .buildingName(area.getBuildingName())
                .floorInfo(area.getFloorInfo())
                .securityLevel(area.getSecurityLevel().name())
                .parentAreaId(area.getParentAreaId())
                .parentAreaName(parentName)
                .status(area.getStatus().name())
                .createdAt(area.getCreatedAt())
                .build();
    }

    public Page<AreaVO> list(String keyword, int page, int size) {
        LambdaQueryWrapper<Area> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Area::getAreaName, keyword)
                    .or().like(Area::getAreaCode, keyword)
                    .or().like(Area::getBuildingName, keyword));
        }
        wrapper.orderByAsc(Area::getAreaCode);

        List<Area> all = areaMapper.selectList(wrapper);
        Page<AreaVO> result = new Page<>(page, size);
        int start = (page - 1) * size;
        int end = Math.min(start + size, all.size());
        if (start < all.size()) {
            result.setRecords(all.subList(start, end).stream().map(a -> AreaVO.builder()
                    .id(a.getId())
                    .areaCode(a.getAreaCode())
                    .areaName(a.getAreaName())
                    .buildingName(a.getBuildingName())
                    .floorInfo(a.getFloorInfo())
                    .securityLevel(a.getSecurityLevel().name())
                    .parentAreaId(a.getParentAreaId())
                    .status(a.getStatus().name())
                    .createdAt(a.getCreatedAt())
                    .build()).toList());
        }
        result.setTotal(all.size());
        return result;
    }

    public Area update(Long id, AreaCreateRequest request) {
        Area area = getById(id);
        area.setAreaName(request.getAreaName());
        area.setBuildingName(request.getBuildingName());
        area.setFloorInfo(request.getFloorInfo());
        if (request.getSecurityLevel() != null) {
            area.setSecurityLevel(request.getSecurityLevel());
        }
        area.setParentAreaId(request.getParentAreaId());
        areaMapper.updateById(area);
        return area;
    }

    public List<Area> getChildren(Long parentAreaId) {
        return areaMapper.selectChildren(parentAreaId);
    }
}

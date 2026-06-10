package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Gate;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface GateMapper extends BaseMapper<Gate> {
    Gate findByGateCode(@Param("gateCode") String gateCode);
    List<Gate> selectByAreaId(@Param("areaId") Long areaId);
}

package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Gate;
import com.visitor.model.vo.GateVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GateMapper extends BaseMapper<Gate> {
    List<GateVO> selectGateListByAreaId(@Param("areaId") Long areaId);
}

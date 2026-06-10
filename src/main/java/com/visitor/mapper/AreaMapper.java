package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Area;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AreaMapper extends BaseMapper<Area> {
    Area findByAreaCode(@Param("areaCode") String areaCode);
    List<Area> selectChildren(@Param("parentAreaId") Long parentAreaId);
}

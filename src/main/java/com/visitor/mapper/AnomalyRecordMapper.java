package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.AnomalyRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnomalyRecordMapper extends BaseMapper<AnomalyRecord> {
}

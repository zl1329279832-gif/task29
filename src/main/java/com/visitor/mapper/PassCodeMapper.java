package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.PassCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

@Mapper
public interface PassCodeMapper extends BaseMapper<PassCode> {
    PassCode findByCode(@Param("code") String code);
    int expirePassCodes(@Param("now") LocalDateTime now);
}

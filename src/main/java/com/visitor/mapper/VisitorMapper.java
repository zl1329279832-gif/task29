package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Visitor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VisitorMapper extends BaseMapper<Visitor> {
    Visitor findByPhone(@Param("phone") String phone);
    Visitor findByIdCard(@Param("idCard") String idCard);
}

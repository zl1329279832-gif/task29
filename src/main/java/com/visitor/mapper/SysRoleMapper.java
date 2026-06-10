package com.visitor.mapper;

import com.visitor.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysRoleMapper {
    SysRole findByRoleCode(@Param("roleCode") String roleCode);
}

package com.visitor.mapper;

import com.visitor.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserMapper {
    SysUser findByUsername(@Param("username") String username);
    SysUser findById(@Param("id") Long id);
    List<String> findRolesByUserId(@Param("userId") Long userId);
    String findDepartmentByUserId(@Param("userId") Long userId);
    List<SysUser> findByRoleCode(@Param("roleCode") String roleCode);
    List<SysUser> findByDepartment(@Param("department") String department);
}

package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Blacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BlacklistMapper extends BaseMapper<Blacklist> {
    Blacklist checkBlacklist(@Param("name") String name,
                              @Param("idCard") String idCard,
                              @Param("phone") String phone);
}

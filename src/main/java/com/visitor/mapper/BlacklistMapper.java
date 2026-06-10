package com.visitor.mapper;

import com.visitor.entity.Blacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BlacklistMapper {
    void insert(Blacklist blacklist);
    Blacklist findById(@Param("id") Long id);
    void updateStatus(@Param("id") Long id, @Param("status") int status);
    List<Blacklist> findActive(@Param("offset") int offset, @Param("limit") int limit);
    long countActive();
    Blacklist findByPhoneAndActive(@Param("phone") String phone);
    Blacklist findByIdCardAndActive(@Param("idCard") String idCard);
    List<Blacklist> findAllActive();
}

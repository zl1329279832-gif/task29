package com.visitor.mapper;

import com.visitor.entity.Visitor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VisitorMapper {
    void insert(Visitor visitor);
    Visitor findById(@Param("id") Long id);
    Visitor findByPhone(@Param("phone") String phone);
    Visitor findByIdCard(@Param("idCard") String idCard);
    void update(Visitor visitor);
    List<Visitor> findAll(@Param("offset") int offset, @Param("limit") int limit);
    long count();
    List<Visitor> search(@Param("keyword") String keyword, @Param("offset") int offset, @Param("limit") int limit);
    long searchCount(@Param("keyword") String keyword);
}

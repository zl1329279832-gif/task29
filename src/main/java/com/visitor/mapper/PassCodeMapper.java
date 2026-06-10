package com.visitor.mapper;

import com.visitor.entity.PassCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PassCodeMapper {
    void insert(PassCode passCode);
    PassCode findById(@Param("id") Long id);
    PassCode findByCode(@Param("code") String code);
    PassCode findActiveByAppointmentId(@Param("appointmentId") Long appointmentId);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    int incrementUsedCount(@Param("id") Long id, @Param("maxUseCount") int maxUseCount);
    void expireByAppointmentId(@Param("appointmentId") Long appointmentId);
    List<PassCode> findExpiredActive(@Param("now") LocalDateTime now);
}

package com.visitor.mapper;

import com.visitor.entity.CheckInRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CheckInRecordMapper {
    void insert(CheckInRecord record);
    CheckInRecord findById(@Param("id") Long id);
    void updateCheckOut(@Param("id") Long id, @Param("checkOutTime") LocalDateTime checkOutTime,
                        @Param("checkOutGateId") String checkOutGateId, @Param("remark") String remark);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    List<CheckInRecord> findCurrentVisitors(@Param("offset") int offset, @Param("limit") int limit);
    long countCurrentVisitors();
    List<CheckInRecord> findByCondition(@Param("visitorId") Long visitorId, @Param("appointmentId") Long appointmentId,
                                         @Param("offset") int offset, @Param("limit") int limit);
    long countByCondition(@Param("visitorId") Long visitorId, @Param("appointmentId") Long appointmentId);
    CheckInRecord findActiveByAppointmentId(@Param("appointmentId") Long appointmentId);
    long countTodayCheckIns(@Param("todayStart") LocalDateTime todayStart, @Param("todayEnd") LocalDateTime todayEnd);
}

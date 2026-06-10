package com.visitor.mapper;

import com.visitor.entity.ApprovalRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApprovalRecordMapper {
    void insert(ApprovalRecord record);
    List<ApprovalRecord> findByAppointmentId(@Param("appointmentId") Long appointmentId);
    List<ApprovalRecord> findByApproverId(@Param("approverId") Long approverId,
                                           @Param("offset") int offset, @Param("limit") int limit);
    long countByApproverId(@Param("approverId") Long approverId);
}

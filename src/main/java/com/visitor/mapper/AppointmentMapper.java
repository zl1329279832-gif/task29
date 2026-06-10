package com.visitor.mapper;

import com.visitor.entity.Appointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AppointmentMapper {
    void insert(Appointment appointment);
    Appointment findById(@Param("id") Long id);
    Appointment findByAppointmentNo(@Param("appointmentNo") String appointmentNo);
    void updateStatus(@Param("id") Long id, @Param("status") String status);
    void update(Appointment appointment);

    List<Appointment> findByCondition(@Param("hostUserId") Long hostUserId,
                                      @Param("createdBy") Long createdBy,
                                      @Param("department") String department,
                                      @Param("status") String status,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);
    long countByCondition(@Param("hostUserId") Long hostUserId,
                          @Param("createdBy") Long createdBy,
                          @Param("department") String department,
                          @Param("status") String status);

    List<Appointment> findDuplicate(@Param("visitorId") Long visitorId,
                                    @Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime,
                                    @Param("excludeId") Long excludeId);

    List<Appointment> findPendingApproval(@Param("department") String department,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);
    long countPendingApproval(@Param("department") String department);

    List<Appointment> findExpiredApproved(@Param("now") LocalDateTime now);
    List<Appointment> findOvertimeCheckedIn(@Param("deadline") LocalDateTime deadline);

    long countTodayAppointments(@Param("todayStart") LocalDateTime todayStart, @Param("todayEnd") LocalDateTime todayEnd);
    long countPendingApprovals();
}

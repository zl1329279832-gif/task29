package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.vo.AppointmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AppointmentMapper extends BaseMapper<Appointment> {
    List<AppointmentVO> selectAppointmentList(@Param("hostId") Long hostId,
                                               @Param("status") String status,
                                               @Param("keyword") String keyword);
    AppointmentVO selectAppointmentDetail(@Param("id") Long id);

    int countDuplicate(@Param("visitorId") Long visitorId,
                       @Param("hostId") Long hostId,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime,
                       @Param("excludeId") Long excludeId);

    List<Appointment> selectExpiredPending(@Param("now") LocalDateTime now);

    List<Appointment> selectCheckedInOverdue(@Param("now") LocalDateTime now);
}

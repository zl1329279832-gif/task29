package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.AreaAuthorization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AreaAuthorizationMapper extends BaseMapper<AreaAuthorization> {
    List<AreaAuthorization> selectByAppointmentAndTime(@Param("appointmentId") Long appointmentId,
                                                        @Param("now") LocalDateTime now);
    int deleteByAppointmentId(@Param("appointmentId") Long appointmentId);
}

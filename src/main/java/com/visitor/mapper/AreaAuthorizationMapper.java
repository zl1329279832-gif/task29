package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.AreaAuthorization;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface AreaAuthorizationMapper extends BaseMapper<AreaAuthorization> {
    AreaAuthorization selectActiveAuth(@Param("appointmentId") Long appointmentId,
                                       @Param("areaId") Long areaId);
    List<AreaAuthorization> selectByAppointment(@Param("appointmentId") Long appointmentId);
    int expireAuthorizations(@Param("now") LocalDateTime now);
}

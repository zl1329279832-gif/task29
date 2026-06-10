package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.AccessLog;
import com.visitor.model.vo.AccessLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLog> {
    List<AccessLogVO> selectAccessLogList(@Param("visitorId") Long visitorId,
                                           @Param("appointmentId") Long appointmentId);

    List<AccessLogVO> selectTrajectory(@Param("appointmentId") Long appointmentId);
}

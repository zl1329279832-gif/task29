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

    /**
     * Count successful ENTRY logs without a matching EXIT for a given appointment.
     * Used to prevent duplicate entry when visitor has not yet departed.
     */
    int countUndepartedEntry(@Param("appointmentId") Long appointmentId);

    /**
     * Check if an idempotent access log already exists (same passCodeId + appointmentId + action).
     * Returns count > 0 if a PASS log already exists for this combination.
     */
    int countExistingPassLog(@Param("passCodeId") Long passCodeId,
                             @Param("appointmentId") Long appointmentId,
                             @Param("action") String action);
}

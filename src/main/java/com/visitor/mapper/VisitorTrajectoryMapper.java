package com.visitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.visitor.model.entity.VisitorTrajectory;
import com.visitor.model.vo.TrajectoryVO;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface VisitorTrajectoryMapper extends BaseMapper<VisitorTrajectory> {
    List<TrajectoryVO> selectTrajectory(@Param("visitorId") Long visitorId,
                                        @Param("appointmentId") Long appointmentId);
    List<VisitorTrajectory> selectCurrentAreaVisitors(@Param("areaId") Long areaId);
    List<VisitorTrajectory> selectOvertimeVisitors(@Param("now") LocalDateTime now,
                                                    @Param("thresholdMinutes") int thresholdMinutes);
}

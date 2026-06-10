package com.visitor.mapper;

import com.visitor.entity.AbnormalPassRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AbnormalPassRecordMapper {
    void insert(AbnormalPassRecord record);
    AbnormalPassRecord findById(@Param("id") Long id);
    void updateHandled(@Param("id") Long id, @Param("handleRemark") String handleRemark);
    List<AbnormalPassRecord> findByCondition(@Param("type") String type, @Param("handled") Integer handled,
                                              @Param("offset") int offset, @Param("limit") int limit);
    long countByCondition(@Param("type") String type, @Param("handled") Integer handled);
    long countToday(@Param("todayStart") LocalDateTime todayStart, @Param("todayEnd") LocalDateTime todayEnd);
}

package com.visitor.mapper;

import com.visitor.entity.BatchImportRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BatchImportRecordMapper {
    void insert(BatchImportRecord record);
    BatchImportRecord findById(@Param("id") Long id);
    void updateResult(@Param("id") Long id, @Param("successCount") int successCount,
                      @Param("failCount") int failCount, @Param("status") String status,
                      @Param("errorDetail") String errorDetail);
}

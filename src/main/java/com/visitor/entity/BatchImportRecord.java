package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BatchImportRecord {
    private Long id;
    private String fileName;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String status;
    private String errorDetail;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

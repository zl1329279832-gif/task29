package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Appointment {
    private Long id;
    private String appointmentNo;
    private Long visitorId;
    private Long hostUserId;
    private String visitReason;
    private LocalDateTime visitStartTime;
    private LocalDateTime visitEndTime;
    private String status;
    private Integer visitorCount;
    private String remark;
    private Long batchImportId;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

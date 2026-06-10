package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CheckInRecord {
    private Long id;
    private Long appointmentId;
    private Long visitorId;
    private Long passCodeId;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private String gateId;
    private String checkOutGateId;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

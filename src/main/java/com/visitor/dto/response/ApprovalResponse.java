package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ApprovalResponse {
    private Long id;
    private Long appointmentId;
    private String appointmentNo;
    private String visitorName;
    private String hostUserName;
    private String visitReason;
    private LocalDateTime visitStartTime;
    private LocalDateTime visitEndTime;
    private String status;
    private String approverName;
    private String action;
    private String comment;
    private LocalDateTime createdAt;
}

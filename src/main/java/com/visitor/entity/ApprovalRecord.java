package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ApprovalRecord {
    private Long id;
    private Long appointmentId;
    private Long approverId;
    private String action;
    private String comment;
    private LocalDateTime createdAt;
}

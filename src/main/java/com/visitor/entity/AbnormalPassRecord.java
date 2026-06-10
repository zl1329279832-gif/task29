package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AbnormalPassRecord {
    private Long id;
    private String visitorName;
    private String visitorPhone;
    private String visitorIdCard;
    private String gateId;
    private String reason;
    private String type;
    private Long operatorId;
    private Integer handled;
    private String handleRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

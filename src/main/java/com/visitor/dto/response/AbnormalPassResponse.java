package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AbnormalPassResponse {
    private Long id;
    private String visitorName;
    private String visitorPhone;
    private String visitorIdCard;
    private String gateId;
    private String reason;
    private String type;
    private String operatorName;
    private Integer handled;
    private String handleRemark;
    private LocalDateTime createdAt;
}

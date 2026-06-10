package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppointmentResponse {
    private Long id;
    private String appointmentNo;
    private Long visitorId;
    private String visitorName;
    private String visitorPhone;
    private String visitorCompany;
    private Long hostUserId;
    private String hostUserName;
    private String visitReason;
    private LocalDateTime visitStartTime;
    private LocalDateTime visitEndTime;
    private String status;
    private Integer visitorCount;
    private String remark;
    private LocalDateTime createdAt;
}

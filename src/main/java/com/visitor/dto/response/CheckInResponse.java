package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class CheckInResponse {
    private Long id;
    private Long appointmentId;
    private String appointmentNo;
    private String visitorName;
    private String visitorPhone;
    private String hostUserName;
    private LocalDateTime checkInTime;
    private LocalDateTime checkOutTime;
    private String gateId;
    private String status;
}

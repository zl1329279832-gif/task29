package com.visitor.model.vo;

import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.VisitTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentVO {
    private Long id;
    private String appointNo;
    private Long visitorId;
    private String visitorName;
    private String visitorPhone;
    private Long hostId;
    private String hostName;
    private String hostDept;
    private VisitTypeEnum visitType;
    private String purpose;
    private LocalDateTime expectedArrive;
    private LocalDateTime expectedLeave;
    private AppointmentStatusEnum status;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private Long rescheduleFrom;
    private String passCode;
    private String passCodeStatus;
    private LocalDateTime createdAt;
}

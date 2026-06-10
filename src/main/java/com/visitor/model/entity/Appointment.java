package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.VisitTypeEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("appointment")
public class Appointment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appointNo;
    private Long visitorId;
    private Long hostId;
    private VisitTypeEnum visitType;
    private String purpose;
    private LocalDateTime expectedArrive;
    private LocalDateTime expectedLeave;
    private AppointmentStatusEnum status;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private String rejectReason;
    private Long rescheduleFrom;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

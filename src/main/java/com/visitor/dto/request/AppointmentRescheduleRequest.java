package com.visitor.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentRescheduleRequest {
    @NotNull(message = "新到访开始时间不能为空")
    private LocalDateTime newVisitStartTime;
    @NotNull(message = "新到访结束时间不能为空")
    private LocalDateTime newVisitEndTime;
    private String remark;
}

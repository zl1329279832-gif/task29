package com.visitor.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppointmentRescheduleRequest {
    @NotNull(message = "新的预计到达时间不能为空")
    private LocalDateTime expectedArrive;
    private LocalDateTime expectedLeave;
}

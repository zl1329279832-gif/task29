package com.visitor.model.dto;

import com.visitor.model.enums.VisitTypeEnum;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AppointmentCreateRequest {
    @NotNull(message = "访客ID不能为空")
    private Long visitorId;
    @NotNull(message = "来访类型不能为空")
    private VisitTypeEnum visitType;
    private String purpose;
    @NotNull(message = "预计到达时间不能为空")
    private LocalDateTime expectedArrive;
    private LocalDateTime expectedLeave;
    private Integer maxCompanions;
}

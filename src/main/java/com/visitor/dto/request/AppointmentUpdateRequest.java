package com.visitor.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentUpdateRequest {
    @NotBlank(message = "来访事由不能为空")
    private String visitReason;
    @NotNull(message = "到访开始时间不能为空")
    private LocalDateTime visitStartTime;
    @NotNull(message = "到访结束时间不能为空")
    private LocalDateTime visitEndTime;
    private Integer visitorCount;
    private String remark;
}

package com.visitor.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppointmentCreateRequest {
    @NotBlank(message = "访客姓名不能为空")
    private String visitorName;
    @NotBlank(message = "访客手机号不能为空")
    private String visitorPhone;
    private String visitorIdCard;
    private String visitorCompany;
    @NotBlank(message = "来访事由不能为空")
    private String visitReason;
    @NotNull(message = "到访开始时间不能为空")
    private LocalDateTime visitStartTime;
    @NotNull(message = "到访结束时间不能为空")
    private LocalDateTime visitEndTime;
    private Integer visitorCount = 1;
    private String remark;
}

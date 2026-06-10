package com.visitor.model.vo;

import com.visitor.model.enums.AccessActionEnum;
import com.visitor.model.enums.AccessResultEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessLogVO {
    private Long id;
    private Long passCodeId;
    private Long visitorId;
    private String visitorName;
    private Long appointmentId;
    private String appointmentNo;
    private AccessActionEnum action;
    private String gateLocation;
    private AccessResultEnum result;
    private String denyReason;
    private Long operatorId;
    private LocalDateTime createdAt;
}

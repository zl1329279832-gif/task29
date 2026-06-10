package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AccessActionEnum;
import com.visitor.model.enums.AccessResultEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("access_log")
public class AccessLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long passCodeId;
    private Long visitorId;
    private Long appointmentId;
    private AccessActionEnum action;
    private String gateLocation;
    private Long gateId;
    private Long areaId;
    private AccessResultEnum result;
    private String denyReason;
    private Long operatorId;
    private LocalDateTime createdAt;
}

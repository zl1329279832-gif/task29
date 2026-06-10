package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AreaAuthStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("area_authorization")
public class AreaAuthorization {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appointmentId;
    private Long visitorId;
    private Long areaId;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private Long grantedBy;
    private AreaAuthStatusEnum status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

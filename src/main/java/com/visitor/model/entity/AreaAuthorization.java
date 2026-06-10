package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
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
    private Long areaId;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private LocalDateTime createdAt;
}

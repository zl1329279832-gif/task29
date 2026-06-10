package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.PassCodeStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("pass_code")
public class PassCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appointmentId;
    private String code;
    private String qrImage;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private PassCodeStatusEnum status;
    private String allowedAreas;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

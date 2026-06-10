package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AnomalyTypeEnum;
import com.visitor.model.enums.AnomalyStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("anomaly_record")
public class AnomalyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long visitorId;
    private Long appointmentId;
    private AnomalyTypeEnum anomalyType;
    private String description;
    private Long securityId;
    private String handleResult;
    private AnomalyStatusEnum status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

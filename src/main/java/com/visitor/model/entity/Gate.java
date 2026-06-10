package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.GateDirectionEnum;
import com.visitor.model.enums.GateStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("gate")
public class Gate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String gateCode;
    private String gateName;
    private Long areaId;
    private GateDirectionEnum direction;
    private GateStatusEnum status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

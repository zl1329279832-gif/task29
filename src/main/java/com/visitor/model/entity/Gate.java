package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.GateTypeEnum;
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
    private String name;
    private Long areaId;
    private String locationDesc;
    private GateTypeEnum gateType;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

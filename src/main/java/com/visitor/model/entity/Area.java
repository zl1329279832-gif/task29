package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AreaStatusEnum;
import com.visitor.model.enums.SecurityLevelEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("area")
public class Area {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String areaCode;
    private String areaName;
    private String buildingName;
    private String floorInfo;
    private SecurityLevelEnum securityLevel;
    private Long parentAreaId;
    private AreaStatusEnum status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

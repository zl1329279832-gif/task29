package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.AreaStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String roomCode;
    private String roomName;
    private Long areaId;
    private String buildingName;
    private String floorInfo;
    private Integer capacity;
    private AreaStatusEnum status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.visitor.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MeetingRoomCreateRequest {
    @NotBlank(message = "会议室编码不能为空")
    private String roomCode;
    @NotBlank(message = "会议室名称不能为空")
    private String roomName;
    @NotNull(message = "区域ID不能为空")
    private Long areaId;
    private String buildingName;
    private String floorInfo;
    private Integer capacity;
}

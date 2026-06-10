package com.visitor.model.vo;

import com.visitor.model.enums.AccessActionEnum;
import com.visitor.model.enums.AccessResultEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrajectoryPointVO {
    private Long accessLogId;
    private AccessActionEnum action;
    private Long gateId;
    private String gateName;
    private Long areaId;
    private String areaName;
    private AccessResultEnum result;
    private LocalDateTime timestamp;
}

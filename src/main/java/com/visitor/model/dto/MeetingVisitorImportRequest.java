package com.visitor.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;

@Data
public class MeetingVisitorImportRequest {
    @NotNull(message = "接待人ID不能为空")
    private Long hostId;
    @NotEmpty(message = "访客列表不能为空")
    private List<MeetingVisitorImportItem> visitors;
}

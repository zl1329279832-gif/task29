package com.visitor.model.vo;

import com.visitor.model.enums.ImportStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatchVO {
    private Long id;
    private Long operatorId;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String failDetail;
    private ImportStatusEnum status;
    private LocalDateTime createdAt;
}

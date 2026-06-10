package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.visitor.model.enums.ImportStatusEnum;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("import_batch")
public class ImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String failDetail;
    private ImportStatusEnum status;
    private LocalDateTime createdAt;
}

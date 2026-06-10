package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class BatchImportResultResponse {
    private Long id;
    private String fileName;
    private Integer totalCount;
    private Integer successCount;
    private Integer failCount;
    private String status;
    private List<ImportError> errors;
    private LocalDateTime createdAt;

    @Data
    public static class ImportError {
        private int rowNumber;
        private String errorMessage;
    }
}

package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PassCodeResponse {
    private Long id;
    private Long appointmentId;
    private String code;
    private String status;
    private Integer maxUseCount;
    private Integer usedCount;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
}

package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class BlacklistResponse {
    private Long id;
    private Long visitorId;
    private String name;
    private String idCard;
    private String phone;
    private String reason;
    private Integer status;
    private String createdByName;
    private LocalDateTime createdAt;
}

package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Blacklist {
    private Long id;
    private Long visitorId;
    private String name;
    private String idCard;
    private String phone;
    private String reason;
    private Integer status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Notification {
    private Long id;
    private Long targetUserId;
    private String type;
    private String title;
    private String content;
    private Integer isRead;
    private Long referenceId;
    private String referenceType;
    private LocalDateTime createdAt;
}

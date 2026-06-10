package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SysRole {
    private Long id;
    private String roleCode;
    private String roleName;
    private String description;
    private LocalDateTime createdAt;
}

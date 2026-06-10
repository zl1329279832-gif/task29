package com.visitor.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class SysUser {
    private Long id;
    private String username;
    private String password;
    private String realName;
    private String phone;
    private String email;
    private String department;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

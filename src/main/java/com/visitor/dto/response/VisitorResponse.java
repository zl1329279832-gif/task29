package com.visitor.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class VisitorResponse {
    private Long id;
    private String name;
    private String phone;
    private String idCard;
    private String company;
    private String email;
    private String photoUrl;
    private LocalDateTime createdAt;
}

package com.visitor.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("visitor")
public class Visitor {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String idCard;
    private String phone;
    private String company;
    private String photoUrl;
    private Integer visitCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

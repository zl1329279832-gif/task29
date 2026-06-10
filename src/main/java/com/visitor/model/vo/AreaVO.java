package com.visitor.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AreaVO {
    private Long id;
    private String name;
    private String building;
    private String description;
    private Integer status;
    private List<GateVO> gates;
}

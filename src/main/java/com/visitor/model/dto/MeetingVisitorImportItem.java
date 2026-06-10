package com.visitor.model.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class MeetingVisitorImportItem {
    private String name;
    private String idCard;
    private String phone;
    private String company;
    private String purpose;
    private LocalDateTime expectedArrive;
    private LocalDateTime expectedLeave;
}

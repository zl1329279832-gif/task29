package com.visitor.controller;

import com.visitor.model.dto.MeetingVisitorImportRequest;
import com.visitor.model.entity.ImportBatch;
import com.visitor.model.vo.ApiResponse;
import com.visitor.model.vo.ImportBatchVO;
import com.visitor.service.ImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping("/meeting-visitors")
    public ApiResponse<ImportBatch> importMeetingVisitors(
            @Valid @RequestBody MeetingVisitorImportRequest request) {
        return ApiResponse.success(importService.importMeetingVisitors(request));
    }

    @GetMapping("/{batchId}/status")
    public ApiResponse<ImportBatchVO> status(@PathVariable Long batchId) {
        return ApiResponse.success(importService.getBatchStatus(batchId));
    }
}

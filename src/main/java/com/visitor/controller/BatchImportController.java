package com.visitor.controller;

import com.visitor.common.result.Result;
import com.visitor.common.util.ExcelUtils;
import com.visitor.dto.response.BatchImportResultResponse;
import com.visitor.service.BatchImportService;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/batch-import")
public class BatchImportController {

    private final BatchImportService batchImportService;

    public BatchImportController(BatchImportService batchImportService) {
        this.batchImportService = batchImportService;
    }

    @PostMapping("/upload")
    public Result<BatchImportResultResponse> upload(@RequestParam("file") MultipartFile file) {
        return Result.ok(batchImportService.importVisitors(file));
    }

    @GetMapping("/{id}/status")
    public Result<BatchImportResultResponse> getStatus(@PathVariable Long id) {
        return Result.ok(batchImportService.getImportStatus(id));
    }

    @GetMapping("/{id}/errors")
    public Result<BatchImportResultResponse> getErrors(@PathVariable Long id) {
        return Result.ok(batchImportService.getImportStatus(id));
    }

    @GetMapping("/template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=visitor_import_template.xlsx");
        try (Workbook workbook = ExcelUtils.createTemplate()) {
            workbook.write(response.getOutputStream());
        }
    }
}

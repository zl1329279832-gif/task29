package com.visitor.service;

import com.visitor.dto.response.BatchImportResultResponse;
import org.springframework.web.multipart.MultipartFile;

public interface BatchImportService {
    BatchImportResultResponse importVisitors(MultipartFile file);
    BatchImportResultResponse getImportStatus(Long id);
}

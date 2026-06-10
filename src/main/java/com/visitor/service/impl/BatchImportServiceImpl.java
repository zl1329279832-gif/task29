package com.visitor.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.common.constant.AppointmentStatus;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.util.ExcelUtils;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.response.BatchImportResultResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.BatchImportRecord;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.BatchImportRecordMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.BatchImportService;
import com.visitor.service.BlacklistService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class BatchImportServiceImpl implements BatchImportService {

    private final BatchImportRecordMapper batchImportRecordMapper;
    private final VisitorMapper visitorMapper;
    private final AppointmentMapper appointmentMapper;
    private final BlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    public BatchImportServiceImpl(BatchImportRecordMapper batchImportRecordMapper, VisitorMapper visitorMapper,
                                   AppointmentMapper appointmentMapper, BlacklistService blacklistService,
                                   ObjectMapper objectMapper) {
        this.batchImportRecordMapper = batchImportRecordMapper;
        this.visitorMapper = visitorMapper;
        this.appointmentMapper = appointmentMapper;
        this.blacklistService = blacklistService;
        this.objectMapper = objectMapper;
    }

    @Override
    public BatchImportResultResponse importVisitors(MultipartFile file) {
        Long currentUserId = SecurityUtils.getCurrentUserId();

        BatchImportRecord importRecord = new BatchImportRecord();
        importRecord.setFileName(file.getOriginalFilename());
        importRecord.setStatus("PROCESSING");
        importRecord.setCreatedBy(currentUserId);
        batchImportRecordMapper.insert(importRecord);

        List<BatchImportResultResponse.ImportError> errors = new ArrayList<>();
        int successCount = 0;
        int totalCount = 0;

        try {
            List<List<String>> rows = ExcelUtils.readExcel(file.getInputStream());
            totalCount = rows.size();
            importRecord.setTotalCount(totalCount);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            // Also support ISO format
            DateTimeFormatter isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

            for (int i = 0; i < rows.size(); i++) {
                int rowNum = i + 2; // Excel row number (header is row 1)
                try {
                    List<String> row = rows.get(i);
                    if (row.size() < 5) {
                        addError(errors, rowNum, "列数不足，至少需要5列");
                        continue;
                    }

                    String visitorName = row.get(0);
                    String phone = row.get(1);
                    String idCard = row.size() > 2 ? row.get(2) : null;
                    String company = row.size() > 3 ? row.get(3) : null;
                    String visitReason = row.get(4);
                    String startTimeStr = row.size() > 5 ? row.get(5) : null;
                    String endTimeStr = row.size() > 6 ? row.get(6) : null;

                    // Validate required fields
                    if (visitorName == null || visitorName.isEmpty()) {
                        addError(errors, rowNum, "访客姓名不能为空");
                        continue;
                    }
                    if (phone == null || phone.isEmpty()) {
                        addError(errors, rowNum, "手机号不能为空");
                        continue;
                    }

                    // Check blacklist
                    if (blacklistService.isBlacklisted(phone, idCard)) {
                        addError(errors, rowNum, "该访客在黑名单中");
                        continue;
                    }

                    // Parse times
                    LocalDateTime startTime = null;
                    LocalDateTime endTime = null;
                    if (startTimeStr != null && !startTimeStr.isEmpty()) {
                        try {
                            startTime = LocalDateTime.parse(startTimeStr, formatter);
                        } catch (DateTimeParseException e1) {
                            try {
                                startTime = LocalDateTime.parse(startTimeStr, isoFormatter);
                            } catch (DateTimeParseException e2) {
                                addError(errors, rowNum, "到访开始时间格式错误");
                                continue;
                            }
                        }
                    }
                    if (endTimeStr != null && !endTimeStr.isEmpty()) {
                        try {
                            endTime = LocalDateTime.parse(endTimeStr, formatter);
                        } catch (DateTimeParseException e1) {
                            try {
                                endTime = LocalDateTime.parse(endTimeStr, isoFormatter);
                            } catch (DateTimeParseException e2) {
                                addError(errors, rowNum, "到访结束时间格式错误");
                                continue;
                            }
                        }
                    }

                    if (startTime == null) startTime = LocalDateTime.now().plusHours(1);
                    if (endTime == null) endTime = startTime.plusHours(2);

                    // Find or create visitor
                    Visitor visitor = visitorMapper.findByPhone(phone);
                    if (visitor == null) {
                        visitor = new Visitor();
                        visitor.setName(visitorName);
                        visitor.setPhone(phone);
                        visitor.setIdCard(idCard);
                        visitor.setCompany(company);
                        visitorMapper.insert(visitor);
                    }

                    // Create appointment
                    Appointment appointment = new Appointment();
                    appointment.setAppointmentNo("VIS" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
                    appointment.setVisitorId(visitor.getId());
                    appointment.setHostUserId(currentUserId);
                    appointment.setVisitReason(visitReason);
                    appointment.setVisitStartTime(startTime);
                    appointment.setVisitEndTime(endTime);
                    appointment.setStatus(AppointmentStatus.PENDING_APPROVAL.name());
                    appointment.setVisitorCount(1);
                    appointment.setBatchImportId(importRecord.getId());
                    appointment.setCreatedBy(currentUserId);
                    appointmentMapper.insert(appointment);

                    successCount++;
                } catch (Exception e) {
                    addError(errors, rowNum, "处理失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new BusinessException("文件解析失败: " + e.getMessage());
        }

        // Update import record
        int failCount = totalCount - successCount;
        String status = failCount == 0 ? "COMPLETED" : (successCount == 0 ? "FAILED" : "PARTIAL_FAIL");
        String errorDetail = null;
        if (!errors.isEmpty()) {
            try {
                errorDetail = objectMapper.writeValueAsString(errors);
            } catch (JsonProcessingException ignored) {
            }
        }
        batchImportRecordMapper.updateResult(importRecord.getId(), successCount, failCount, status, errorDetail);

        return buildResponse(importRecord.getId(), importRecord.getFileName(), totalCount, successCount, failCount, status, errors);
    }

    @Override
    public BatchImportResultResponse getImportStatus(Long id) {
        BatchImportRecord record = batchImportRecordMapper.findById(id);
        if (record == null) throw new BusinessException("导入记录不存在");

        List<BatchImportResultResponse.ImportError> errors = new ArrayList<>();
        if (record.getErrorDetail() != null) {
            try {
                errors = objectMapper.readValue(record.getErrorDetail(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, BatchImportResultResponse.ImportError.class));
            } catch (JsonProcessingException ignored) {
            }
        }

        return buildResponse(record.getId(), record.getFileName(), record.getTotalCount(),
                record.getSuccessCount(), record.getFailCount(), record.getStatus(), errors);
    }

    private void addError(List<BatchImportResultResponse.ImportError> errors, int rowNum, String message) {
        BatchImportResultResponse.ImportError error = new BatchImportResultResponse.ImportError();
        error.setRowNumber(rowNum);
        error.setErrorMessage(message);
        errors.add(error);
    }

    private BatchImportResultResponse buildResponse(Long id, String fileName, int total, int success, int fail,
                                                     String status, List<BatchImportResultResponse.ImportError> errors) {
        BatchImportResultResponse response = new BatchImportResultResponse();
        response.setId(id);
        response.setFileName(fileName);
        response.setTotalCount(total);
        response.setSuccessCount(success);
        response.setFailCount(fail);
        response.setStatus(status);
        response.setErrors(errors);
        return response;
    }
}

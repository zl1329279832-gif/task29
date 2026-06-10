package com.visitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.ImportBatchMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.model.dto.MeetingVisitorImportItem;
import com.visitor.model.dto.MeetingVisitorImportRequest;
import com.visitor.model.dto.VisitorRegisterRequest;
import com.visitor.model.dto.AppointmentCreateRequest;
import com.visitor.model.entity.*;
import com.visitor.model.entity.ImportBatch;
import com.visitor.model.enums.AppointmentStatusEnum;
import com.visitor.model.enums.ImportStatusEnum;
import com.visitor.model.enums.VisitTypeEnum;
import com.visitor.model.vo.ImportBatchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final AppointmentMapper appointmentMapper;
    private final ImportBatchMapper importBatchMapper;
    private final SysUserMapper sysUserMapper;
    private final VisitorService visitorService;
    private final AppointmentService appointmentService;
    private final BlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    /**
     * Batch import meeting visitors
     * Each row is processed independently - partial failure is allowed
     */
    public ImportBatch importMeetingVisitors(MeetingVisitorImportRequest request) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser operator = sysUserMapper.findByUsername(username);

        List<MeetingVisitorImportItem> visitors = request.getVisitors();
        if (visitors == null || visitors.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_DATA_EMPTY);
        }

        // Create batch record
        ImportBatch batch = ImportBatch.builder()
                .operatorId(operator.getId())
                .totalCount(visitors.size())
                .successCount(0)
                .failCount(0)
                .status(ImportStatusEnum.PROCESSING)
                .build();
        importBatchMapper.insert(batch);

        List<Map<String, Object>> failDetails = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        // Process each visitor independently
        for (int i = 0; i < visitors.size(); i++) {
            MeetingVisitorImportItem item = visitors.get(i);
            int rowNum = i + 1;
            try {
                // Validate required fields
                if (!StringUtils.hasText(item.getName())) {
                    throw new IllegalArgumentException("Name is required");
                }
                if (item.getExpectedArrive() == null) {
                    throw new IllegalArgumentException("Expected arrival time is required");
                }

                // Register or find visitor
                VisitorRegisterRequest visitorReq = new VisitorRegisterRequest();
                visitorReq.setName(item.getName());
                visitorReq.setIdCard(item.getIdCard());
                visitorReq.setPhone(item.getPhone());
                visitorReq.setCompany(item.getCompany());
                Visitor visitor = visitorService.registerOrFind(visitorReq);

                // Blacklist check - skip this visitor if blacklisted
                Blacklist bl = blacklistService.check(visitor.getName(), visitor.getIdCard(), visitor.getPhone());
                if (bl != null) {
                    throw new IllegalArgumentException("Blacklisted: " + bl.getReason());
                }

                // Create appointment (set the host to the specified hostId)
                AppointmentCreateRequest apptReq = new AppointmentCreateRequest();
                apptReq.setVisitorId(visitor.getId());
                apptReq.setVisitType(VisitTypeEnum.MEETING);
                apptReq.setPurpose(item.getPurpose());
                apptReq.setExpectedArrive(item.getExpectedArrive());
                apptReq.setExpectedLeave(item.getExpectedLeave());

                // We need to temporarily set the security context to the host
                // For batch import, we create appointments under the specified host
                createAppointmentForHost(request.getHostId(), apptReq, visitor);

                successCount++;
            } catch (Exception e) {
                failCount++;
                Map<String, Object> failDetail = new HashMap<>();
                failDetail.put("row", rowNum);
                failDetail.put("name", item.getName());
                failDetail.put("reason", e.getMessage());
                failDetails.add(failDetail);
                log.warn("Import row {} failed: {}", rowNum, e.getMessage());
            }
        }

        // Update batch record
        batch.setSuccessCount(successCount);
        batch.setFailCount(failCount);

        if (failCount == 0) {
            batch.setStatus(ImportStatusEnum.COMPLETED);
        } else if (successCount == 0) {
            batch.setStatus(ImportStatusEnum.FAILED);
        } else {
            batch.setStatus(ImportStatusEnum.PARTIAL_FAIL);
        }

        try {
            batch.setFailDetail(objectMapper.writeValueAsString(failDetails));
        } catch (JsonProcessingException e) {
            batch.setFailDetail("[]");
        }

        importBatchMapper.updateById(batch);

        log.info("Batch import completed: total={}, success={}, fail={}",
                visitors.size(), successCount, failCount);
        return batch;
    }

    public ImportBatchVO getBatchStatus(Long batchId) {
        ImportBatch batch = importBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Import batch not found");
        }
        return ImportBatchVO.builder()
                .id(batch.getId())
                .operatorId(batch.getOperatorId())
                .totalCount(batch.getTotalCount())
                .successCount(batch.getSuccessCount())
                .failCount(batch.getFailCount())
                .failDetail(batch.getFailDetail())
                .status(batch.getStatus())
                .createdAt(batch.getCreatedAt())
                .build();
    }

    /**
     * Create appointment for a specific host (used in batch import)
     */
    private void createAppointmentForHost(Long hostId, AppointmentCreateRequest request, Visitor visitor) {
        SysUser host = sysUserMapper.selectById(hostId);
        if (host == null) {
            throw new IllegalArgumentException("Host not found");
        }

        String appointNo = "APT" + System.currentTimeMillis()
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(100, 999);

        Appointment appointment = Appointment.builder()
                .appointNo(appointNo)
                .visitorId(visitor.getId())
                .hostId(hostId)
                .visitType(VisitTypeEnum.MEETING)
                .purpose(request.getPurpose())
                .expectedArrive(request.getExpectedArrive())
                .expectedLeave(request.getExpectedLeave())
                .status(AppointmentStatusEnum.PENDING)
                .build();

        appointmentMapper.insert(appointment);
    }
}

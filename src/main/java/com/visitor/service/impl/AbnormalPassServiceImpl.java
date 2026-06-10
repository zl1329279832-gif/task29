package com.visitor.service.impl;

import com.visitor.common.exception.BusinessException;
import com.visitor.common.result.PageResult;
import com.visitor.common.util.SecurityUtils;
import com.visitor.dto.request.AbnormalPassRequest;
import com.visitor.dto.response.AbnormalPassResponse;
import com.visitor.entity.AbnormalPassRecord;
import com.visitor.entity.SysUser;
import com.visitor.mapper.AbnormalPassRecordMapper;
import com.visitor.mapper.SysUserMapper;
import com.visitor.service.AbnormalPassService;
import com.visitor.service.NotificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class AbnormalPassServiceImpl implements AbnormalPassService {

    private final AbnormalPassRecordMapper abnormalPassRecordMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;

    public AbnormalPassServiceImpl(AbnormalPassRecordMapper abnormalPassRecordMapper,
                                    SysUserMapper sysUserMapper, NotificationService notificationService) {
        this.abnormalPassRecordMapper = abnormalPassRecordMapper;
        this.sysUserMapper = sysUserMapper;
        this.notificationService = notificationService;
    }

    @Override
    public AbnormalPassResponse record(AbnormalPassRequest request) {
        AbnormalPassRecord record = new AbnormalPassRecord();
        record.setVisitorName(request.getVisitorName());
        record.setVisitorPhone(request.getVisitorPhone());
        record.setVisitorIdCard(request.getVisitorIdCard());
        record.setGateId(request.getGateId());
        record.setReason(request.getReason());
        record.setType(request.getType());
        record.setOperatorId(SecurityUtils.getCurrentUserId());
        record.setHandled(0);
        abnormalPassRecordMapper.insert(record);

        // Send alert to security
        notificationService.sendAbnormalAlert(
                request.getVisitorName() != null ? request.getVisitorName() : "未知访客",
                request.getReason());

        return toResponse(record);
    }

    @Override
    public PageResult<AbnormalPassResponse> list(String type, Integer handled, int page, int size) {
        int offset = (page - 1) * size;
        List<AbnormalPassRecord> records = abnormalPassRecordMapper.findByCondition(type, handled, offset, size);
        long total = abnormalPassRecordMapper.countByCondition(type, handled);
        List<AbnormalPassResponse> responses = records.stream().map(this::toResponse).collect(Collectors.toList());
        return PageResult.of(responses, total, page, size);
    }

    @Override
    public void handle(Long id, String handleRemark) {
        AbnormalPassRecord record = abnormalPassRecordMapper.findById(id);
        if (record == null) throw new BusinessException("异常放行记录不存在");
        if (record.getHandled() == 1) throw new BusinessException("该记录已处理");
        abnormalPassRecordMapper.updateHandled(id, handleRemark);
    }

    private AbnormalPassResponse toResponse(AbnormalPassRecord record) {
        AbnormalPassResponse response = new AbnormalPassResponse();
        response.setId(record.getId());
        response.setVisitorName(record.getVisitorName());
        response.setVisitorPhone(record.getVisitorPhone());
        response.setVisitorIdCard(record.getVisitorIdCard());
        response.setGateId(record.getGateId());
        response.setReason(record.getReason());
        response.setType(record.getType());
        if (record.getOperatorId() != null) {
            SysUser operator = sysUserMapper.findById(record.getOperatorId());
            if (operator != null) response.setOperatorName(operator.getRealName());
        }
        response.setHandled(record.getHandled());
        response.setHandleRemark(record.getHandleRemark());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }
}

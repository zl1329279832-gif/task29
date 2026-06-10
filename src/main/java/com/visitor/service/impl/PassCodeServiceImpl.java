package com.visitor.service.impl;

import com.visitor.common.constant.PassCodeStatus;
import com.visitor.common.constant.RedisKeyConstants;
import com.visitor.common.exception.BusinessException;
import com.visitor.common.util.PassCodeGenerator;
import com.visitor.dto.response.PassCodeResponse;
import com.visitor.entity.Appointment;
import com.visitor.entity.PassCode;
import com.visitor.entity.Visitor;
import com.visitor.mapper.AppointmentMapper;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.mapper.VisitorMapper;
import com.visitor.service.BlacklistService;
import com.visitor.service.PassCodeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class PassCodeServiceImpl implements PassCodeService {

    private final PassCodeMapper passCodeMapper;
    private final AppointmentMapper appointmentMapper;
    private final VisitorMapper visitorMapper;
    private final PassCodeGenerator passCodeGenerator;
    private final StringRedisTemplate redisTemplate;
    private final BlacklistService blacklistService;

    @Value("${passcode.default-max-use:2}")
    private int defaultMaxUse;

    @Value("${passcode.early-entry-minutes:30}")
    private int earlyEntryMinutes;

    public PassCodeServiceImpl(PassCodeMapper passCodeMapper, AppointmentMapper appointmentMapper,
                                VisitorMapper visitorMapper, PassCodeGenerator passCodeGenerator,
                                StringRedisTemplate redisTemplate, BlacklistService blacklistService) {
        this.passCodeMapper = passCodeMapper;
        this.appointmentMapper = appointmentMapper;
        this.visitorMapper = visitorMapper;
        this.passCodeGenerator = passCodeGenerator;
        this.redisTemplate = redisTemplate;
        this.blacklistService = blacklistService;
    }

    @Override
    @Transactional
    public PassCodeResponse generate(Long appointmentId) {
        Appointment appointment = appointmentMapper.findById(appointmentId);
        if (appointment == null) throw new BusinessException("预约不存在");

        // Revoke any existing active pass codes
        PassCode existing = passCodeMapper.findActiveByAppointmentId(appointmentId);
        if (existing != null) {
            passCodeMapper.updateStatus(existing.getId(), PassCodeStatus.REVOKED.name());
            redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + existing.getCode());
        }

        String code = passCodeGenerator.generate(appointmentId);
        LocalDateTime validFrom = appointment.getVisitStartTime().minusMinutes(earlyEntryMinutes);
        LocalDateTime validUntil = appointment.getVisitEndTime();

        PassCode passCode = new PassCode();
        passCode.setAppointmentId(appointmentId);
        passCode.setCode(code);
        passCode.setStatus(PassCodeStatus.ACTIVE.name());
        passCode.setMaxUseCount(defaultMaxUse);
        passCode.setUsedCount(0);
        passCode.setValidFrom(validFrom);
        passCode.setValidUntil(validUntil);
        passCodeMapper.insert(passCode);

        // Cache in Redis
        long ttlSeconds = Duration.between(LocalDateTime.now(), validUntil).getSeconds();
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue().set(
                    RedisKeyConstants.PASS_CODE_PREFIX + code,
                    String.valueOf(passCode.getId()),
                    ttlSeconds, TimeUnit.SECONDS);
        }

        return toResponse(passCode);
    }

    @Override
    @Transactional
    public PassCodeResponse verify(String code) {
        // Verify HMAC signature
        if (!passCodeGenerator.verify(code)) {
            throw new BusinessException("通行码无效：签名校验失败");
        }

        // Find pass code
        PassCode passCode = passCodeMapper.findByCode(code);
        if (passCode == null) {
            throw new BusinessException("通行码不存在");
        }

        // Check status
        if (!PassCodeStatus.ACTIVE.name().equals(passCode.getStatus())) {
            throw new BusinessException("通行码已" + (PassCodeStatus.USED.name().equals(passCode.getStatus()) ? "使用" :
                    PassCodeStatus.EXPIRED.name().equals(passCode.getStatus()) ? "过期" : "撤销"));
        }

        // Check time validity
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(passCode.getValidFrom())) {
            throw new BusinessException("通行码尚未生效，生效时间: " + passCode.getValidFrom());
        }
        if (now.isAfter(passCode.getValidUntil())) {
            passCodeMapper.updateStatus(passCode.getId(), PassCodeStatus.EXPIRED.name());
            redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + code);
            throw new BusinessException("通行码已过期");
        }

        // Check blacklist via appointment's visitor
        Appointment appointment = appointmentMapper.findById(passCode.getAppointmentId());
        if (appointment != null) {
            Visitor visitor = visitorMapper.findById(appointment.getVisitorId());
            if (visitor != null && blacklistService.isBlacklisted(visitor.getPhone(), visitor.getIdCard())) {
                throw new BusinessException("该访客在黑名单中，禁止通行");
            }
        }

        // Distributed lock to prevent concurrent use
        String lockKey = RedisKeyConstants.PASS_CODE_LOCK_PREFIX + code;
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 5, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException("通行码正在使用中，请稍后重试");
        }

        try {
            // Optimistic lock: DB increment
            int affected = passCodeMapper.incrementUsedCount(passCode.getId(), passCode.getMaxUseCount());
            if (affected == 0) {
                throw new BusinessException("通行码使用次数已达上限");
            }

            // Update status if max reached
            passCode.setUsedCount(passCode.getUsedCount() + 1);
            if (passCode.getUsedCount() >= passCode.getMaxUseCount()) {
                passCodeMapper.updateStatus(passCode.getId(), PassCodeStatus.USED.name());
                redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + code);
                passCode.setStatus(PassCodeStatus.USED.name());
            }
        } finally {
            redisTemplate.delete(lockKey);
        }

        return toResponse(passCode);
    }

    @Override
    @Transactional
    public void revoke(Long id) {
        PassCode passCode = passCodeMapper.findById(id);
        if (passCode == null) throw new BusinessException("通行码不存在");
        if (!PassCodeStatus.ACTIVE.name().equals(passCode.getStatus())) {
            throw new BusinessException("只能撤销有效的通行码");
        }
        passCodeMapper.updateStatus(id, PassCodeStatus.REVOKED.name());
        redisTemplate.delete(RedisKeyConstants.PASS_CODE_PREFIX + passCode.getCode());
    }

    @Override
    public PassCodeResponse getByAppointmentId(Long appointmentId) {
        PassCode passCode = passCodeMapper.findActiveByAppointmentId(appointmentId);
        if (passCode == null) {
            throw new BusinessException("该预约没有有效的通行码");
        }
        return toResponse(passCode);
    }

    private PassCodeResponse toResponse(PassCode passCode) {
        PassCodeResponse response = new PassCodeResponse();
        response.setId(passCode.getId());
        response.setAppointmentId(passCode.getAppointmentId());
        response.setCode(passCode.getCode());
        response.setStatus(passCode.getStatus());
        response.setMaxUseCount(passCode.getMaxUseCount());
        response.setUsedCount(passCode.getUsedCount());
        response.setValidFrom(passCode.getValidFrom());
        response.setValidUntil(passCode.getValidUntil());
        return response;
    }
}

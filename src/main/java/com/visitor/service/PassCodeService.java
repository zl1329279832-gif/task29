package com.visitor.service;

import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.PassCodeMapper;
import com.visitor.model.entity.Appointment;
import com.visitor.model.entity.PassCode;
import com.visitor.model.enums.PassCodeStatusEnum;
import com.visitor.model.vo.PassCodeVO;
import com.visitor.util.QrCodeUtil;
import com.visitor.util.RedisLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PassCodeService {

    private final PassCodeMapper passCodeMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisLock redisLock;

    @Value("${visitor.passcode.hmac-key}")
    private String hmacKey;

    @Value("${visitor.passcode.default-max-uses}")
    private int defaultMaxUses;

    @Value("${visitor.passcode.qr-size}")
    private int qrSize;

    private static final String PASS_CODE_CACHE_PREFIX = "visitor:passcode:";
    private static final String PASS_CODE_SCAN_LOCK_PREFIX = "visitor:scan:";

    /**
     * Generate pass code for an approved appointment
     */
    public PassCode generateForAppointment(Appointment appointment) {
        // Check if already exists
        PassCode existing = getPassCodeByAppointmentId(appointment.getId());
        if (existing != null) {
            return existing;
        }

        String code = QrCodeUtil.generatePassCode(hmacKey);
        String qrImage = QrCodeUtil.generateQrCodeBase64(code, qrSize);

        PassCode passCode = PassCode.builder()
                .appointmentId(appointment.getId())
                .code(code)
                .qrImage(qrImage)
                .maxUses(defaultMaxUses)
                .usedCount(0)
                .validFrom(appointment.getExpectedArrive())
                .validTo(appointment.getExpectedLeave() != null
                        ? appointment.getExpectedLeave()
                        : appointment.getExpectedArrive().plusHours(8))
                .status(PassCodeStatusEnum.ACTIVE)
                .build();

        passCodeMapper.insert(passCode);

        // Cache in Redis
        cachePassCode(passCode);

        log.info("Generated pass code for appointment {}: {}", appointment.getId(), code);
        return passCode;
    }

    /**
     * Verify and use a pass code (for gate check-in/check-out)
     * Uses distributed lock to prevent concurrent scanning
     */
    public PassCode verifyAndUse(String code, String scanPurpose) {
        // 1. Verify HMAC signature
        if (!QrCodeUtil.verifyPassCode(code, hmacKey)) {
            throw new BizException(ErrorCode.PASS_CODE_INVALID);
        }

        // 2. Acquire distributed lock to prevent duplicate scanning
        String lockKey = PASS_CODE_SCAN_LOCK_PREFIX + code;
        String lockValue = redisLock.tryLock(lockKey);
        if (lockValue == null) {
            throw new BizException(ErrorCode.PASS_CODE_DUPLICATE_SCAN);
        }

        try {
            // 3. Get pass code (cache first, then DB)
            PassCode passCode = getPassCodeByCode(code);
            if (passCode == null) {
                throw new BizException(ErrorCode.PASS_CODE_INVALID);
            }

            // 4. Check status
            if (passCode.getStatus() == PassCodeStatusEnum.EXPIRED) {
                throw new BizException(ErrorCode.PASS_CODE_EXPIRED);
            }
            if (passCode.getStatus() == PassCodeStatusEnum.REVOKED) {
                throw new BizException(ErrorCode.PASS_CODE_REVOKED);
            }
            if (passCode.getStatus() == PassCodeStatusEnum.USED_UP) {
                throw new BizException(ErrorCode.PASS_CODE_USED_UP);
            }

            // 5. Check validity period
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(passCode.getValidFrom()) || now.isAfter(passCode.getValidTo())) {
                passCode.setStatus(PassCodeStatusEnum.EXPIRED);
                passCodeMapper.updateById(passCode);
                evictCache(code);
                throw new BizException(ErrorCode.PASS_CODE_EXPIRED);
            }

            // 6. Check usage count
            if (passCode.getUsedCount() >= passCode.getMaxUses()) {
                passCode.setStatus(PassCodeStatusEnum.USED_UP);
                passCodeMapper.updateById(passCode);
                evictCache(code);
                throw new BizException(ErrorCode.PASS_CODE_USED_UP);
            }

            // 7. Increment usage count
            passCode.setUsedCount(passCode.getUsedCount() + 1);
            if (passCode.getUsedCount() >= passCode.getMaxUses()) {
                passCode.setStatus(PassCodeStatusEnum.USED_UP);
            }
            passCodeMapper.updateById(passCode);
            cachePassCode(passCode);

            return passCode;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    /**
     * Revoke pass code for an appointment (e.g., on cancellation/reschedule)
     */
    public void revokeByAppointmentId(Long appointmentId) {
        PassCode passCode = getPassCodeByAppointmentId(appointmentId);
        if (passCode != null && passCode.getStatus() == PassCodeStatusEnum.ACTIVE) {
            passCode.setStatus(PassCodeStatusEnum.REVOKED);
            passCodeMapper.updateById(passCode);
            evictCache(passCode.getCode());
            log.info("Revoked pass code for appointment {}", appointmentId);
        }
    }

    public PassCodeVO getPassCodeVO(Long appointmentId) {
        PassCode passCode = getPassCodeByAppointmentId(appointmentId);
        if (passCode == null) {
            throw new BizException(ErrorCode.PASS_CODE_INVALID);
        }
        return PassCodeVO.builder()
                .appointmentId(passCode.getAppointmentId())
                .code(passCode.getCode())
                .qrImage(passCode.getQrImage())
                .maxUses(passCode.getMaxUses())
                .usedCount(passCode.getUsedCount())
                .validFrom(passCode.getValidFrom())
                .validTo(passCode.getValidTo())
                .status(passCode.getStatus().name())
                .build();
    }

    public PassCode getPassCodeByAppointmentId(Long appointmentId) {
        return passCodeMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PassCode>()
                        .eq(PassCode::getAppointmentId, appointmentId));
    }

    private PassCode getPassCodeByCode(String code) {
        // Try Redis cache first
        String cacheKey = PASS_CODE_CACHE_PREFIX + code;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached instanceof PassCode) {
            return (PassCode) cached;
        }
        // Fallback to DB
        PassCode passCode = passCodeMapper.findByCode(code);
        if (passCode != null) {
            cachePassCode(passCode);
        }
        return passCode;
    }

    private void cachePassCode(PassCode passCode) {
        String cacheKey = PASS_CODE_CACHE_PREFIX + passCode.getCode();
        redisTemplate.opsForValue().set(cacheKey, passCode, 24, TimeUnit.HOURS);
    }

    private void evictCache(String code) {
        redisTemplate.delete(PASS_CODE_CACHE_PREFIX + code);
    }

    public int expirePassCodes() {
        return passCodeMapper.expirePassCodes(LocalDateTime.now());
    }
}

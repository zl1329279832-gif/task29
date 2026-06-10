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
     * Generate pass code for an approved appointment.
     * Idempotent: returns existing pass code if already generated.
     */
    public PassCode generateForAppointment(Appointment appointment) {
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
        cachePassCode(passCode);

        log.info("Generated pass code for appointment {}: {}", appointment.getId(), code);
        return passCode;
    }

    // ── Phase 1: Read-only validation ───────────────────────────────────

    /**
     * Verify a scanned pass code WITHOUT consuming a use.
     * Acquires a short-lived scan lock to block truly concurrent duplicate scans,
     * performs full read-only validation, then releases the lock.
     *
     * Caller MUST hold an appointment-level lock before calling confirmUsage().
     */
    public PassCode verifyForScan(String code) {
        // 1. HMAC signature check
        if (!QrCodeUtil.verifyPassCode(code, hmacKey)) {
            throw new BizException(ErrorCode.PASS_CODE_INVALID);
        }

        // 2. Short-lived scan lock (blocks the same code scanned at the same instant)
        String lockKey = PASS_CODE_SCAN_LOCK_PREFIX + code;
        String lockValue = redisLock.tryLock(lockKey);
        if (lockValue == null) {
            throw new BizException(ErrorCode.PASS_CODE_DUPLICATE_SCAN);
        }

        try {
            // 3. Always read fresh from DB to avoid stale cache
            PassCode passCode = passCodeMapper.findByCode(code);
            if (passCode == null) {
                throw new BizException(ErrorCode.PASS_CODE_INVALID);
            }

            // 4. Status guards
            assertPassCodeUsable(passCode);

            // 5. Validity window
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(passCode.getValidFrom())) {
                throw new BizException(ErrorCode.PASS_CODE_NOT_YET_VALID);
            }
            if (now.isAfter(passCode.getValidTo())) {
                passCode.setStatus(PassCodeStatusEnum.EXPIRED);
                passCodeMapper.updateById(passCode);
                evictCache(code);
                throw new BizException(ErrorCode.PASS_CODE_EXPIRED);
            }

            // 6. Remaining uses
            if (passCode.getUsedCount() >= passCode.getMaxUses()) {
                passCode.setStatus(PassCodeStatusEnum.USED_UP);
                passCodeMapper.updateById(passCode);
                evictCache(code);
                throw new BizException(ErrorCode.PASS_CODE_USED_UP);
            }

            return passCode;
        } finally {
            redisLock.unlock(lockKey, lockValue);
        }
    }

    // ── Phase 2: Commit usage ───────────────────────────────────────────

    /**
     * Atomically consume one use of the pass code.
     * MUST be called inside the caller's distributed lock (appointment-level)
     * after ALL validations (blacklist, appointment state, undeparted check) have passed.
     *
     * Re-reads from DB to prevent TOCTOU races.
     */
    public PassCode confirmUsage(Long passCodeId) {
        PassCode passCode = passCodeMapper.selectById(passCodeId);
        if (passCode == null) {
            throw new BizException(ErrorCode.PASS_CODE_INVALID);
        }

        // Re-validate state from fresh DB read
        assertPassCodeUsable(passCode);

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(passCode.getValidFrom()) || now.isAfter(passCode.getValidTo())) {
            passCode.setStatus(PassCodeStatusEnum.EXPIRED);
            passCodeMapper.updateById(passCode);
            evictCache(passCode.getCode());
            throw new BizException(ErrorCode.PASS_CODE_EXPIRED);
        }

        if (passCode.getUsedCount() >= passCode.getMaxUses()) {
            passCode.setStatus(PassCodeStatusEnum.USED_UP);
            passCodeMapper.updateById(passCode);
            evictCache(passCode.getCode());
            throw new BizException(ErrorCode.PASS_CODE_USED_UP);
        }

        // Increment usage
        passCode.setUsedCount(passCode.getUsedCount() + 1);
        if (passCode.getUsedCount() >= passCode.getMaxUses()) {
            passCode.setStatus(PassCodeStatusEnum.USED_UP);
        }
        passCodeMapper.updateById(passCode);
        cachePassCode(passCode);

        log.info("Confirmed pass code usage: id={}, code={}, usedCount={}/{}",
                passCodeId, passCode.getCode(), passCode.getUsedCount(), passCode.getMaxUses());
        return passCode;
    }

    // ── Legacy single-phase method (kept for backwards compat in tests) ──

    /**
     * @deprecated Prefer verifyForScan + confirmUsage for gate flows.
     */
    @Deprecated
    public PassCode verifyAndUse(String code, String scanPurpose) {
        PassCode passCode = verifyForScan(code);
        return confirmUsage(passCode.getId());
    }

    // ── Revoke / expire ─────────────────────────────────────────────────

    /**
     * Revoke pass code for an appointment (cancellation / reschedule).
     * Idempotent: no-op if already revoked or non-existent.
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

    public int expirePassCodes() {
        return passCodeMapper.expirePassCodes(LocalDateTime.now());
    }

    // ── Query helpers ───────────────────────────────────────────────────

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

    public PassCode getPassCodeById(Long id) {
        return passCodeMapper.selectById(id);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private void assertPassCodeUsable(PassCode passCode) {
        switch (passCode.getStatus()) {
            case EXPIRED:
                throw new BizException(ErrorCode.PASS_CODE_EXPIRED);
            case REVOKED:
                throw new BizException(ErrorCode.PASS_CODE_REVOKED);
            case USED_UP:
                throw new BizException(ErrorCode.PASS_CODE_USED_UP);
            default:
                break;
        }
    }

    private void cachePassCode(PassCode passCode) {
        String cacheKey = PASS_CODE_CACHE_PREFIX + passCode.getCode();
        redisTemplate.opsForValue().set(cacheKey, passCode, 24, TimeUnit.HOURS);
    }

    private void evictCache(String code) {
        redisTemplate.delete(PASS_CODE_CACHE_PREFIX + code);
    }
}

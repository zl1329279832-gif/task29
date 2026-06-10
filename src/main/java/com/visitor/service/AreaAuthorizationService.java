package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaAuthorizationMapper;
import com.visitor.mapper.GateMapper;
import com.visitor.model.entity.AreaAuthorization;
import com.visitor.model.entity.Gate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaAuthorizationService {

    private final AreaAuthorizationMapper areaAuthorizationMapper;
    private final GateMapper gateMapper;

    /**
     * Create a single area authorization for an appointment.
     */
    public AreaAuthorization createAuthorization(Long appointmentId, Long areaId,
                                                  LocalDateTime validFrom, LocalDateTime validTo) {
        AreaAuthorization auth = AreaAuthorization.builder()
                .appointmentId(appointmentId)
                .areaId(areaId)
                .validFrom(validFrom)
                .validTo(validTo)
                .build();
        areaAuthorizationMapper.insert(auth);
        log.info("Created area authorization: appointment={}, area={}, valid=[{}, {}]",
                appointmentId, areaId, validFrom, validTo);
        return auth;
    }

    /**
     * Create batch area authorizations for an appointment.
     */
    public void createBatchAuthorizations(Long appointmentId, List<Long> areaIds,
                                          LocalDateTime validFrom, LocalDateTime validTo) {
        for (Long areaId : areaIds) {
            createAuthorization(appointmentId, areaId, validFrom, validTo);
        }
    }

    /**
     * Check if a visitor's appointment has valid access to a specific area at the given time.
     */
    public boolean isAreaAccessAllowed(Long appointmentId, Long areaId, LocalDateTime now) {
        long count = areaAuthorizationMapper.selectCount(
                new LambdaQueryWrapper<AreaAuthorization>()
                        .eq(AreaAuthorization::getAppointmentId, appointmentId)
                        .eq(AreaAuthorization::getAreaId, areaId)
                        .le(AreaAuthorization::getValidFrom, now)
                        .ge(AreaAuthorization::getValidTo, now));
        return count > 0;
    }

    /**
     * Get all authorized areas for an appointment.
     */
    public List<AreaAuthorization> getAuthorizedAreas(Long appointmentId) {
        return areaAuthorizationMapper.selectList(
                new LambdaQueryWrapper<AreaAuthorization>()
                        .eq(AreaAuthorization::getAppointmentId, appointmentId));
    }

    /**
     * Get currently valid authorizations for an appointment.
     */
    public List<AreaAuthorization> getValidAuthorizations(Long appointmentId, LocalDateTime now) {
        return areaAuthorizationMapper.selectByAppointmentAndTime(appointmentId, now);
    }

    /**
     * Revoke all area authorizations for an appointment.
     */
    public void revokeByAppointmentId(Long appointmentId) {
        int deleted = areaAuthorizationMapper.deleteByAppointmentId(appointmentId);
        if (deleted > 0) {
            log.info("Revoked {} area authorizations for appointment {}", deleted, appointmentId);
        }
    }

    /**
     * Validate gate access: resolves gate → area → checks authorization.
     * Throws BizException if access is not allowed.
     */
    public void validateGateAccess(Long appointmentId, Long gateId, LocalDateTime now) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new BizException(ErrorCode.GATE_NOT_FOUND);
        }

        if (!isAreaAccessAllowed(appointmentId, gate.getAreaId(), now)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_AREA_ACCESS,
                    "预约未授权进入门岗所在区域");
        }
    }

    /**
     * Count the number of authorized areas for an appointment.
     * Used to compute dynamic maxUses for pass codes.
     */
    public int countAuthorizedAreas(Long appointmentId) {
        return Math.toIntExact(areaAuthorizationMapper.selectCount(
                new LambdaQueryWrapper<AreaAuthorization>()
                        .eq(AreaAuthorization::getAppointmentId, appointmentId)));
    }
}

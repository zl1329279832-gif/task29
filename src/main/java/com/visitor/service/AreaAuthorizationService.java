package com.visitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.visitor.exception.BizException;
import com.visitor.exception.ErrorCode;
import com.visitor.mapper.AreaAuthorizationMapper;
import com.visitor.model.entity.Area;
import com.visitor.model.entity.AreaAuthorization;
import com.visitor.model.entity.MeetingRoom;
import com.visitor.model.enums.AreaAuthStatusEnum;
import com.visitor.model.vo.AreaAuthorizationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AreaAuthorizationService {

    private final AreaAuthorizationMapper areaAuthorizationMapper;
    private final AreaService areaService;
    private final MeetingRoomService meetingRoomService;

    /**
     * Grant area access authorization for a visitor's appointment.
     * Idempotent: if active auth already exists for same appointment+area, returns existing.
     */
    public AreaAuthorization grantAuthorization(Long appointmentId, Long visitorId, Long areaId,
                                                 LocalDateTime validFrom, LocalDateTime validTo,
                                                 Long grantedBy) {
        // Validate area exists
        areaService.getById(areaId);

        // Idempotent: check existing active auth
        AreaAuthorization existing = areaAuthorizationMapper.selectActiveAuth(appointmentId, areaId);
        if (existing != null) {
            log.info("Area authorization already exists for appointment {} area {}", appointmentId, areaId);
            return existing;
        }

        AreaAuthorization auth = AreaAuthorization.builder()
                .appointmentId(appointmentId)
                .visitorId(visitorId)
                .areaId(areaId)
                .validFrom(validFrom)
                .validTo(validTo)
                .grantedBy(grantedBy)
                .status(AreaAuthStatusEnum.ACTIVE)
                .build();
        areaAuthorizationMapper.insert(auth);
        log.info("Granted area authorization: appointment={}, area={}", appointmentId, areaId);
        return auth;
    }

    /**
     * Batch grant area authorizations based on meeting room.
     * Grants access to the meeting room's area and all parent areas (path to root).
     */
    @Transactional
    public List<AreaAuthorization> batchGrantForMeetingRoom(Long appointmentId, Long visitorId,
                                                            Long meetingRoomId,
                                                            LocalDateTime validFrom, LocalDateTime validTo,
                                                            Long grantedBy) {
        MeetingRoom room = meetingRoomService.getById(meetingRoomId);
        List<AreaAuthorization> results = new ArrayList<>();

        // Grant access to the meeting room's area
        AreaAuthorization auth = grantAuthorization(appointmentId, visitorId, room.getAreaId(),
                validFrom, validTo, grantedBy);
        results.add(auth);

        // Grant access to all parent areas (path to root)
        Long parentAreaId = areaService.getById(room.getAreaId()).getParentAreaId();
        while (parentAreaId != null) {
            Area parent = areaService.getById(parentAreaId);
            AreaAuthorization parentAuth = grantAuthorization(appointmentId, visitorId, parent.getId(),
                    validFrom, validTo, grantedBy);
            results.add(parentAuth);
            parentAreaId = parent.getParentAreaId();
        }

        log.info("Batch granted {} area authorizations for appointment {} via meeting room {}",
                results.size(), appointmentId, meetingRoomId);
        return results;
    }

    /**
     * Check if a visitor (by appointment) is authorized to access a specific area.
     */
    public boolean checkAuthorization(Long appointmentId, Long areaId) {
        AreaAuthorization auth = areaAuthorizationMapper.selectActiveAuth(appointmentId, areaId);
        return auth != null;
    }

    /**
     * Revoke all area authorizations for an appointment.
     */
    @Transactional
    public void revokeByAppointment(Long appointmentId) {
        List<AreaAuthorization> auths = areaAuthorizationMapper.selectByAppointment(appointmentId);
        for (AreaAuthorization auth : auths) {
            if (auth.getStatus() == AreaAuthStatusEnum.ACTIVE) {
                auth.setStatus(AreaAuthStatusEnum.REVOKED);
                areaAuthorizationMapper.updateById(auth);
            }
        }
        log.info("Revoked all area authorizations for appointment {}", appointmentId);
    }

    /**
     * Get all authorizations for an appointment.
     */
    public List<AreaAuthorizationVO> getByAppointment(Long appointmentId) {
        List<AreaAuthorization> auths = areaAuthorizationMapper.selectByAppointment(appointmentId);
        return auths.stream().map(auth -> {
            String areaName = "";
            try {
                Area area = areaService.getById(auth.getAreaId());
                areaName = area.getAreaName();
            } catch (Exception ignored) {}
            return AreaAuthorizationVO.builder()
                    .id(auth.getId())
                    .appointmentId(auth.getAppointmentId())
                    .visitorId(auth.getVisitorId())
                    .areaId(auth.getAreaId())
                    .areaName(areaName)
                    .validFrom(auth.getValidFrom())
                    .validTo(auth.getValidTo())
                    .status(auth.getStatus().name())
                    .createdAt(auth.getCreatedAt())
                    .build();
        }).toList();
    }

    /**
     * Revoke a single authorization by ID.
     */
    public void revokeById(Long id) {
        AreaAuthorization auth = areaAuthorizationMapper.selectById(id);
        if (auth == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "授权记录不存在");
        }
        if (auth.getStatus() == AreaAuthStatusEnum.ACTIVE) {
            auth.setStatus(AreaAuthStatusEnum.REVOKED);
            areaAuthorizationMapper.updateById(auth);
            log.info("Revoked area authorization {}", id);
        }
    }

    /**
     * Expire stale authorizations (called by scheduler).
     */
    public int expireAuthorizations() {
        return areaAuthorizationMapper.expireAuthorizations(LocalDateTime.now());
    }

    /**
     * Build comma-separated allowed area IDs string for pass code.
     */
    public String buildAllowedAreasString(Long appointmentId) {
        List<AreaAuthorization> auths = areaAuthorizationMapper.selectByAppointment(appointmentId);
        return auths.stream()
                .filter(a -> a.getStatus() == AreaAuthStatusEnum.ACTIVE)
                .map(a -> a.getAreaId().toString())
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}

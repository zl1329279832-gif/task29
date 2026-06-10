package com.visitor.service;

import com.visitor.model.enums.RoleEnum;
import com.visitor.websocket.VisitorWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    private final VisitorWebSocketHandler webSocketHandler;

    private static final int MAX_PUSH_RETRIES = 2;

    // ── Push methods with retry ─────────────────────────────────────────

    /**
     * Push visitor arrival notification to host employee.
     */
    public void pushVisitorArrived(String hostUserId, String visitorName, String appointmentNo) {
        Map<String, Object> message = buildMessage("VISITOR_ARRIVED",
                "visitorName", visitorName,
                "appointmentNo", appointmentNo,
                "message", "访客 " + visitorName + " 已到达");

        pushToUserWithRetry(hostUserId, message, "visitorArrived");
    }

    /**
     * Push anomaly alert to all security staff.
     */
    public void pushAnomalyAlert(String anomalyType, String visitorName, String description) {
        Map<String, Object> message = buildMessage("ANOMALY_ALERT",
                "anomalyType", anomalyType,
                "visitorName", visitorName,
                "description", description,
                "message", "异常通行告警：" + description);

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "anomalyAlert");
    }

    /**
     * Push blacklist interception alert to security.
     */
    public void pushBlacklistAlert(String visitorName, String reason, String location) {
        Map<String, Object> message = buildMessage("BLACKLIST_ALERT",
                "visitorName", visitorName,
                "reason", reason,
                "location", location,
                "message", "黑名单访客 " + visitorName + " 尝试通行，已拦截");

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "blacklistAlert");
    }

    /**
     * Push approval reminder to admins.
     */
    public void pushApprovalReminder(String appointmentNo, String visitorName, String hostName) {
        Map<String, Object> message = buildMessage("APPROVAL_REMINDER",
                "appointmentNo", appointmentNo,
                "visitorName", visitorName,
                "hostName", hostName,
                "message", "新预约待审批：" + visitorName + " 来访 " + hostName);

        pushToRoleWithRetry(RoleEnum.ADMIN.name(), message, "approvalReminder");
    }

    /**
     * Push approval result to the employee who created the appointment.
     */
    public void pushApprovalResult(String hostUserId, String appointmentNo,
                                    boolean approved, String reason) {
        Map<String, Object> message = buildMessage("APPROVAL_RESULT",
                "appointmentNo", appointmentNo,
                "approved", approved,
                "reason", reason,
                "message", approved ? "预约已通过审批" : "预约被拒绝：" + reason);

        pushToUserWithRetry(hostUserId, message, "approvalResult");
    }

    /**
     * Push undeparted visitor warning to security.
     */
    public void pushUndepartedWarning(String visitorName, String appointmentNo, long overdueMinutes) {
        Map<String, Object> message = buildMessage("UNDEPARTED_WARNING",
                "visitorName", visitorName,
                "appointmentNo", appointmentNo,
                "overdueMinutes", overdueMinutes,
                "message", "访客 " + visitorName + " 已超时 " + overdueMinutes + " 分钟未离场");

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "undepartedWarning");
    }

    /**
     * Push pass code revoked notification (used during reschedule).
     */
    public void pushPassCodeRevoked(String hostUserId, String appointmentNo, String reason) {
        Map<String, Object> message = buildMessage("PASS_CODE_REVOKED",
                "appointmentNo", appointmentNo,
                "reason", reason,
                "message", "通行码已撤销：" + reason);

        pushToUserWithRetry(hostUserId, message, "passCodeRevoked");
    }

    /**
     * Push gate status change notification (consistent state sync).
     */
    public void pushGateStatusChange(String targetUserId, String appointmentNo,
                                      String gateAction, String newStatus) {
        Map<String, Object> message = buildMessage("GATE_STATUS_CHANGE",
                "appointmentNo", appointmentNo,
                "gateAction", gateAction,
                "newStatus", newStatus,
                "message", "门禁状态变更：" + gateAction + " → " + newStatus);

        pushToUserWithRetry(targetUserId, message, "gateStatusChange");
    }

    /**
     * Push area violation alert to security.
     */
    public void pushAreaViolationAlert(String visitorName, String areaName,
                                        String gateName, String description) {
        Map<String, Object> message = buildMessage("AREA_VIOLATION",
                "visitorName", visitorName,
                "areaName", areaName,
                "gateName", gateName,
                "description", description,
                "message", "区域越权告警：访客 " + visitorName + " 在 " + gateName + " 尝试进入未授权区域");

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "areaViolation");
    }

    /**
     * Push companion anomaly alert to security.
     */
    public void pushCompanionAnomalyAlert(String visitorName, int actualCount,
                                           int maxAllowed, String gateName) {
        Map<String, Object> message = buildMessage("COMPANION_ANOMALY",
                "visitorName", visitorName,
                "actualCount", actualCount,
                "maxAllowed", maxAllowed,
                "gateName", gateName,
                "message", "随行人员异常：访客 " + visitorName + " 随行 "
                        + actualCount + " 人，超过限制 " + maxAllowed + " 人");

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "companionAnomaly");
    }

    /**
     * Push trajectory update to security.
     */
    public void pushTrajectoryUpdate(String visitorName, String gateName,
                                      String areaName, String action) {
        Map<String, Object> message = buildMessage("TRAJECTORY_UPDATE",
                "visitorName", visitorName,
                "gateName", gateName,
                "areaName", areaName,
                "action", action,
                "message", "访客轨迹更新：" + visitorName + " " + action + " " + gateName);

        pushToRoleWithRetry(RoleEnum.SECURITY.name(), message, "trajectoryUpdate");
    }

    // ── Internal retry helpers ──────────────────────────────────────────

    private void pushToUserWithRetry(String userId, Map<String, Object> message, String pushType) {
        boolean success = false;
        for (int attempt = 0; attempt <= MAX_PUSH_RETRIES; attempt++) {
            try {
                webSocketHandler.pushToUser(userId, message);
                success = true;
                break;
            } catch (Exception e) {
                log.warn("Push {} to user {} failed (attempt {}/{}): {}",
                        pushType, userId, attempt + 1, MAX_PUSH_RETRIES + 1, e.getMessage());
            }
        }
        if (success) {
            log.info("Push {} to user {} succeeded", pushType, userId);
        } else {
            log.error("Push {} to user {} FAILED after {} attempts — DB state and client may be inconsistent",
                    pushType, userId, MAX_PUSH_RETRIES + 1);
        }
    }

    private void pushToRoleWithRetry(String role, Map<String, Object> message, String pushType) {
        boolean success = false;
        for (int attempt = 0; attempt <= MAX_PUSH_RETRIES; attempt++) {
            try {
                webSocketHandler.pushToRole(role, message);
                success = true;
                break;
            } catch (Exception e) {
                log.warn("Push {} to role {} failed (attempt {}/{}): {}",
                        pushType, role, attempt + 1, MAX_PUSH_RETRIES + 1, e.getMessage());
            }
        }
        if (success) {
            log.info("Push {} to role {} succeeded", pushType, role);
        } else {
            log.error("Push {} to role {} FAILED after {} attempts",
                    pushType, role, MAX_PUSH_RETRIES + 1);
        }
    }

    private Map<String, Object> buildMessage(String type, Object... kvPairs) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", type);
        message.put("timestamp", LocalDateTime.now().toString());
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            message.put(String.valueOf(kvPairs[i]), kvPairs[i + 1]);
        }
        return message;
    }
}

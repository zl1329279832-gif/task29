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

    /**
     * Push visitor arrival notification to host employee
     */
    public void pushVisitorArrived(String hostUserId, String visitorName, String appointmentNo) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "VISITOR_ARRIVED");
        message.put("visitorName", visitorName);
        message.put("appointmentNo", appointmentNo);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", "访客 " + visitorName + " 已到达");

        webSocketHandler.pushToUser(hostUserId, message);
        log.info("Pushed visitor arrival to host {}: visitor={}", hostUserId, visitorName);
    }

    /**
     * Push anomaly alert to all security staff
     */
    public void pushAnomalyAlert(String anomalyType, String visitorName, String description) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "ANOMALY_ALERT");
        message.put("anomalyType", anomalyType);
        message.put("visitorName", visitorName);
        message.put("description", description);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", "异常通行告警：" + description);

        webSocketHandler.pushToRole(RoleEnum.SECURITY.name(), message);
        log.info("Pushed anomaly alert to security: type={}, visitor={}", anomalyType, visitorName);
    }

    /**
     * Push blacklist interception alert to security
     */
    public void pushBlacklistAlert(String visitorName, String reason, String location) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "BLACKLIST_ALERT");
        message.put("visitorName", visitorName);
        message.put("reason", reason);
        message.put("location", location);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", "黑名单访客 " + visitorName + " 尝试通行，已拦截");

        webSocketHandler.pushToRole(RoleEnum.SECURITY.name(), message);
        log.info("Pushed blacklist alert to security: visitor={}", visitorName);
    }

    /**
     * Push approval reminder to admins
     */
    public void pushApprovalReminder(String appointmentNo, String visitorName, String hostName) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "APPROVAL_REMINDER");
        message.put("appointmentNo", appointmentNo);
        message.put("visitorName", visitorName);
        message.put("hostName", hostName);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", "新预约待审批：" + visitorName + " 来访 " + hostName);

        webSocketHandler.pushToRole(RoleEnum.ADMIN.name(), message);
        log.info("Pushed approval reminder to admins: appointment={}", appointmentNo);
    }

    /**
     * Push approval result to the employee who created the appointment
     */
    public void pushApprovalResult(String hostUserId, String appointmentNo, boolean approved, String reason) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "APPROVAL_RESULT");
        message.put("appointmentNo", appointmentNo);
        message.put("approved", approved);
        message.put("reason", reason);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", approved ? "预约已通过审批" : "预约被拒绝：" + reason);

        webSocketHandler.pushToUser(hostUserId, message);
        log.info("Pushed approval result to host {}: appointment={}, approved={}", hostUserId, appointmentNo, approved);
    }

    /**
     * Push undeparted visitor warning to security
     */
    public void pushUndepartedWarning(String visitorName, String appointmentNo, long overdueMinutes) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "UNDEPARTED_WARNING");
        message.put("visitorName", visitorName);
        message.put("appointmentNo", appointmentNo);
        message.put("overdueMinutes", overdueMinutes);
        message.put("timestamp", LocalDateTime.now().toString());
        message.put("message", "访客 " + visitorName + " 已超时 " + overdueMinutes + " 分钟未离场");

        webSocketHandler.pushToRole(RoleEnum.SECURITY.name(), message);
        log.info("Pushed undeparted warning: visitor={}, overdue={}min", visitorName, overdueMinutes);
    }
}

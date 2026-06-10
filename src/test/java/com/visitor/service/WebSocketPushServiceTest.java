package com.visitor.service;

import com.visitor.model.enums.RoleEnum;
import com.visitor.websocket.VisitorWebSocketHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketPushServiceTest {

    @InjectMocks
    private WebSocketPushService webSocketPushService;

    @Mock
    private VisitorWebSocketHandler webSocketHandler;

    // ── Push to user ────────────────────────────────────────────────────

    @Test
    @DisplayName("pushVisitorArrived sends correct message to host")
    void pushVisitorArrived_sendsCorrectMessage() {
        webSocketPushService.pushVisitorArrived("42", "Li Si", "APT001");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToUser(eq("42"), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("VISITOR_ARRIVED", message.get("type"));
        assertEquals("Li Si", message.get("visitorName"));
        assertEquals("APT001", message.get("appointmentNo"));
        assertNotNull(message.get("timestamp"));
    }

    @Test
    @DisplayName("pushApprovalResult sends approved message")
    void pushApprovalResult_approved() {
        webSocketPushService.pushApprovalResult("1", "APT001", true, "LGTM");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToUser(eq("1"), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("APPROVAL_RESULT", message.get("type"));
        assertEquals(true, message.get("approved"));
        assertEquals("LGTM", message.get("reason"));
    }

    @Test
    @DisplayName("pushApprovalResult sends rejected message")
    void pushApprovalResult_rejected() {
        webSocketPushService.pushApprovalResult("1", "APT001", false, "Not suitable");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToUser(eq("1"), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals(false, message.get("approved"));
        assertEquals("Not suitable", message.get("reason"));
    }

    // ── Push to role ────────────────────────────────────────────────────

    @Test
    @DisplayName("pushBlacklistAlert sends to SECURITY role")
    void pushBlacklistAlert_sendsToSecurity() {
        webSocketPushService.pushBlacklistAlert("Bad Person", "Violence", "Main Gate");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToRole(eq(RoleEnum.SECURITY.name()), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("BLACKLIST_ALERT", message.get("type"));
        assertEquals("Bad Person", message.get("visitorName"));
        assertEquals("Violence", message.get("reason"));
        assertEquals("Main Gate", message.get("location"));
    }

    @Test
    @DisplayName("pushAnomalyAlert sends to SECURITY role")
    void pushAnomalyAlert_sendsToSecurity() {
        webSocketPushService.pushAnomalyAlert("TAILGATING", "Li Si", "Tailgating at gate A");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToRole(eq(RoleEnum.SECURITY.name()), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("ANOMALY_ALERT", message.get("type"));
        assertEquals("TAILGATING", message.get("anomalyType"));
    }

    @Test
    @DisplayName("pushApprovalReminder sends to ADMIN role")
    void pushApprovalReminder_sendsToAdmin() {
        webSocketPushService.pushApprovalReminder("APT001", "Li Si", "Zhang San");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToRole(eq(RoleEnum.ADMIN.name()), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("APPROVAL_REMINDER", message.get("type"));
        assertEquals("APT001", message.get("appointmentNo"));
    }

    @Test
    @DisplayName("pushUndepartedWarning sends to SECURITY role")
    void pushUndepartedWarning_sendsToSecurity() {
        webSocketPushService.pushUndepartedWarning("Li Si", "APT001", 60);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToRole(eq(RoleEnum.SECURITY.name()), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("UNDEPARTED_WARNING", message.get("type"));
        assertEquals(60L, message.get("overdueMinutes"));
    }

    // ── Retry on failure ────────────────────────────────────────────────

    @Test
    @DisplayName("pushToUser retries on failure")
    void pushToUser_retriesOnFailure() {
        doThrow(new RuntimeException("WS closed"))
                .doThrow(new RuntimeException("WS closed"))
                .doNothing()
                .when(webSocketHandler).pushToUser(eq("1"), any(Map.class));

        // Should not throw — retry succeeds on 3rd attempt
        webSocketPushService.pushVisitorArrived("1", "Li Si", "APT001");

        verify(webSocketHandler, times(3)).pushToUser(eq("1"), any(Map.class));
    }

    @Test
    @DisplayName("pushToRole retries on failure then gives up gracefully")
    void pushToRole_retriesThenGivesUp() {
        doThrow(new RuntimeException("WS closed"))
                .when(webSocketHandler).pushToRole(eq(RoleEnum.SECURITY.name()), any(Map.class));

        // Should not throw — logs error but does not propagate
        assertDoesNotThrow(() ->
                webSocketPushService.pushBlacklistAlert("Bad", "Reason", "Gate"));

        // 1 initial + 2 retries = 3 total attempts
        verify(webSocketHandler, times(3)).pushToRole(eq(RoleEnum.SECURITY.name()), any(Map.class));
    }

    // ── New push methods ────────────────────────────────────────────────

    @Test
    @DisplayName("pushPassCodeRevoked sends to user")
    void pushPassCodeRevoked() {
        webSocketPushService.pushPassCodeRevoked("1", "APT001", "Rescheduled");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToUser(eq("1"), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("PASS_CODE_REVOKED", message.get("type"));
        assertEquals("Rescheduled", message.get("reason"));
    }

    @Test
    @DisplayName("pushGateStatusChange sends to user")
    void pushGateStatusChange() {
        webSocketPushService.pushGateStatusChange("1", "APT001", "ENTRY", "CHECKED_IN");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webSocketHandler).pushToUser(eq("1"), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertEquals("GATE_STATUS_CHANGE", message.get("type"));
        assertEquals("ENTRY", message.get("gateAction"));
        assertEquals("CHECKED_IN", message.get("newStatus"));
    }
}

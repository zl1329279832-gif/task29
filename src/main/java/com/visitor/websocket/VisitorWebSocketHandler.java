package com.visitor.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    // userId -> list of sessions
    private static final Map<String, List<WebSocketSession>> USER_SESSIONS = new ConcurrentHashMap<>();
    // role -> list of sessions
    private static final Map<String, List<WebSocketSession>> ROLE_SESSIONS = new ConcurrentHashMap<>();

    private static final AtomicInteger TOTAL_SENT = new AtomicInteger(0);
    private static final AtomicInteger TOTAL_FAILED = new AtomicInteger(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = getParam(session, "userId");
        String role = getParam(session, "role");

        if (userId != null) {
            USER_SESSIONS.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
        }
        if (role != null) {
            ROLE_SESSIONS.computeIfAbsent(role, k -> new CopyOnWriteArrayList<>()).add(session);
        }
        log.info("WebSocket connected: userId={}, role={}, sessionId={}", userId, role, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = getParam(session, "userId");
        String role = getParam(session, "role");

        removeSession(USER_SESSIONS, userId, session);
        removeSession(ROLE_SESSIONS, role, session);

        log.info("WebSocket disconnected: userId={}, role={}, sessionId={}, status={}",
                userId, role, session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket transport error on session {}: {}", session.getId(), exception.getMessage());
        // Remove the faulty session
        String userId = getParam(session, "userId");
        String role = getParam(session, "role");
        removeSession(USER_SESSIONS, userId, session);
        removeSession(ROLE_SESSIONS, role, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received WebSocket message: {}", message.getPayload());
    }

    // ── Public push methods ─────────────────────────────────────────────

    /**
     * Push message to a specific user (all their sessions).
     * Closed/failed sessions are auto-removed.
     */
    public void pushToUser(String userId, Map<String, Object> message) {
        List<WebSocketSession> sessions = USER_SESSIONS.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active sessions for user {}, push skipped", userId);
            return;
        }
        sendToSessions(sessions, message, "user:" + userId);
    }

    /**
     * Push message to all users with a specific role.
     * Closed/failed sessions are auto-removed.
     */
    public void pushToRole(String role, Map<String, Object> message) {
        List<WebSocketSession> sessions = ROLE_SESSIONS.get(role);
        if (sessions == null || sessions.isEmpty()) {
            log.debug("No active sessions for role {}, push skipped", role);
            return;
        }
        sendToSessions(sessions, message, "role:" + role);
    }

    /**
     * Broadcast to all connected clients.
     */
    public void broadcast(Map<String, Object> message) {
        USER_SESSIONS.values().forEach(sessions ->
                sendToSessions(sessions, message, "broadcast"));
    }

    // ── Diagnostics ─────────────────────────────────────────────────────

    public int getTotalSent() {
        return TOTAL_SENT.get();
    }

    public int getTotalFailed() {
        return TOTAL_FAILED.get();
    }

    public int getActiveSessionCount() {
        return USER_SESSIONS.values().stream().mapToInt(List::size).sum();
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private void sendToSessions(List<WebSocketSession> sessions, Map<String, Object> message, String target) {
        List<WebSocketSession> toRemove = new java.util.ArrayList<>();

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                toRemove.add(session);
                continue;
            }
            try {
                String json = objectMapper.writeValueAsString(message);
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(json));
                        TOTAL_SENT.incrementAndGet();
                    } else {
                        toRemove.add(session);
                    }
                }
            } catch (IOException e) {
                TOTAL_FAILED.incrementAndGet();
                log.warn("Failed to send WebSocket message to session {} (target={}): {}",
                        session.getId(), target, e.getMessage());
                toRemove.add(session);
            }
        }

        // Clean up dead sessions
        sessions.removeAll(toRemove);
    }

    private void removeSession(Map<String, List<WebSocketSession>> sessionMap,
                                String key, WebSocketSession session) {
        if (key == null) return;
        List<WebSocketSession> sessions = sessionMap.get(key);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionMap.remove(key);
            }
        }
    }

    private String getParam(WebSocketSession session, String param) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(param)) {
                    return kv[1];
                }
            }
        }
        return null;
    }
}

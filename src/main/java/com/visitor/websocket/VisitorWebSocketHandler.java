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

@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;

    // userId -> list of sessions
    private static final Map<String, List<WebSocketSession>> USER_SESSIONS = new ConcurrentHashMap<>();
    // role -> list of sessions
    private static final Map<String, List<WebSocketSession>> ROLE_SESSIONS = new ConcurrentHashMap<>();

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

        if (userId != null) {
            List<WebSocketSession> sessions = USER_SESSIONS.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) USER_SESSIONS.remove(userId);
            }
        }
        if (role != null) {
            List<WebSocketSession> sessions = ROLE_SESSIONS.get(role);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) ROLE_SESSIONS.remove(role);
            }
        }
        log.info("WebSocket disconnected: userId={}, role={}, sessionId={}", userId, role, session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Received WebSocket message: {}", message.getPayload());
    }

    /**
     * Push message to a specific user
     */
    public void pushToUser(String userId, Map<String, Object> message) {
        List<WebSocketSession> sessions = USER_SESSIONS.get(userId);
        if (sessions != null) {
            sessions.forEach(s -> sendMessage(s, message));
        }
    }

    /**
     * Push message to all users with a specific role
     */
    public void pushToRole(String role, Map<String, Object> message) {
        List<WebSocketSession> sessions = ROLE_SESSIONS.get(role);
        if (sessions != null) {
            sessions.forEach(s -> sendMessage(s, message));
        }
    }

    /**
     * Broadcast to all connected clients
     */
    public void broadcast(Map<String, Object> message) {
        USER_SESSIONS.values().forEach(sessions ->
                sessions.forEach(s -> sendMessage(s, message)));
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session.isOpen()) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error("Failed to send WebSocket message to session {}: {}", session.getId(), e.getMessage());
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

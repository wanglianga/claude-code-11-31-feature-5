package com.mdm.platform.security;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 轻量令牌存储：登录签发不透明令牌，带 TTL。
 */
@Component
public class TokenService {

    public record Session(String token, String username, String displayName, String role, Instant expireAt) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Value("${app.security.token-ttl-hours:12}")
    private long ttlHours;

    public Session issue(String username, String displayName, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Session session = new Session(token, username, displayName, role,
                Instant.now().plusSeconds(ttlHours * 3600));
        sessions.put(token, session);
        return session;
    }

    public Session verify(String token) {
        if (token == null) {
            return null;
        }
        Session session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expireAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return null;
        }
        return session;
    }
}

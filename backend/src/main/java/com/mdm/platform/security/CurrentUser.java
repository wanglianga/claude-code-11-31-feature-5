package com.mdm.platform.security;

public final class CurrentUser {
    private static final ThreadLocal<TokenService.Session> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    public static void set(TokenService.Session session) {
        HOLDER.set(session);
    }

    public static TokenService.Session get() {
        return HOLDER.get();
    }

    public static String username() {
        TokenService.Session s = HOLDER.get();
        return s == null ? "system" : s.username();
    }

    public static boolean canWrite() {
        TokenService.Session s = HOLDER.get();
        return s != null && ("ADMIN".equals(s.role()) || "ENGINEER".equals(s.role()));
    }

    public static void clear() {
        HOLDER.remove();
    }
}

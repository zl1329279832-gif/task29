package com.visitor.common.constant;

public final class RedisKeyConstants {
    private RedisKeyConstants() {}

    public static final String PASS_CODE_PREFIX = "passcode:";
    public static final String PASS_CODE_LOCK_PREFIX = "passcode:lock:";
    public static final String PASS_CODE_USED_PREFIX = "passcode:used:";
    public static final String JWT_BLACKLIST_PREFIX = "jwt:blacklist:";
    public static final String BLACKLIST_PHONE = "blacklist:phone";
    public static final String BLACKLIST_IDCARD = "blacklist:idcard";
    public static final String USER_DETAILS_PREFIX = "user:details:";
    public static final String APPOINTMENT_DUPLICATE_PREFIX = "appointment:duplicate:";
}

package com.visitor.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    SUCCESS(200, "操作成功"),

    // Auth errors 401xxx
    AUTH_FAILED(401001, "用户名或密码错误"),
    TOKEN_EXPIRED(401002, "Token已过期"),
    TOKEN_INVALID(401003, "Token无效"),
    ACCOUNT_DISABLED(401004, "账号已禁用"),
    UNAUTHORIZED(401005, "未授权访问"),

    // Permission errors 403xxx
    FORBIDDEN(403001, "权限不足"),
    DATA_ACCESS_DENIED(403002, "无权访问该数据"),

    // Business errors 400xxx
    PARAM_INVALID(400001, "参数校验失败"),
    DUPLICATE_APPOINTMENT(400002, "重复预约：该访客在相同时间段已有预约"),
    APPOINTMENT_NOT_FOUND(400003, "预约不存在"),
    APPOINTMENT_STATUS_INVALID(400004, "预约状态不允许此操作"),
    PASS_CODE_EXPIRED(400005, "通行码已过期"),
    PASS_CODE_USED_UP(400006, "通行码使用次数已耗尽"),
    PASS_CODE_REVOKED(400007, "通行码已撤销"),
    PASS_CODE_INVALID(400008, "通行码无效"),
    PASS_CODE_DUPLICATE_SCAN(400009, "请勿重复扫码"),
    VISITOR_NOT_FOUND(400010, "访客不存在"),
    BLACKLIST_HIT(400011, "该访客在黑名单中，禁止通行"),
    ALREADY_CHECKED_IN(400012, "访客已签到，请勿重复签到"),
    NOT_CHECKED_IN(400013, "访客尚未签到"),
    ALREADY_DEPARTED(400014, "访客已离场"),
    APPOINTMENT_EXPIRED(400015, "预约已过期"),
    RESCHEDULE_NOT_ALLOWED(400016, "当前状态不允许改期"),
    VISITOR_BLACKLISTED(400017, "访客已被列入黑名单"),
    IMPORT_DATA_EMPTY(400018, "导入数据为空"),
    PASS_CODE_NOT_YET_VALID(400019, "通行码尚未生效"),
    NO_REENTRY_WITHOUT_EXIT(400020, "访客尚未离场，不允许重复入场"),
    BATCH_DUPLICATE_VISITOR(400021, "批次内访客重复"),
    APPROVAL_ALREADY_PROCESSED(400022, "审批已处理，请勿重复操作"),
    GATE_NOT_FOUND(400023, "门岗不存在"),
    GATE_INACTIVE(400024, "门岗未启用"),
    AREA_NOT_FOUND(400025, "区域不存在"),
    AREA_UNAUTHORIZED(400026, "未授权进入该区域"),
    MEETING_ROOM_NOT_FOUND(400027, "会议室不存在"),
    COMPANION_ANOMALY(400028, "同行人异常"),
    AREA_OVERTIME_STAY(400029, "区域超时停留"),

    // System errors 500xxx
    SYSTEM_ERROR(500001, "系统内部错误"),
    REDIS_ERROR(500002, "Redis操作异常"),
    QR_GENERATE_FAILED(500003, "二维码生成失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}

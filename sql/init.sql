-- =============================================
-- 访客预约与门禁联动系统 - 数据库初始化脚本
-- =============================================

CREATE DATABASE IF NOT EXISTS visitor_management DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE visitor_management;

-- =============================================
-- 系统用户表
-- =============================================
CREATE TABLE sys_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录用户名',
    password        VARCHAR(256) NOT NULL COMMENT 'BCrypt加密密码',
    real_name       VARCHAR(64)  NOT NULL COMMENT '真实姓名',
    phone           VARCHAR(20)  COMMENT '手机号',
    email           VARCHAR(128) COMMENT '邮箱',
    department      VARCHAR(128) COMMENT '所属部门',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用, 1-启用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_department (department)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- =============================================
-- 角色表
-- =============================================
CREATE TABLE sys_role (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code       VARCHAR(32)  NOT NULL UNIQUE COMMENT '角色编码',
    role_name       VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description     VARCHAR(256) COMMENT '角色描述',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- =============================================
-- 用户角色关联表
-- =============================================
CREATE TABLE sys_user_role (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    role_id         BIGINT       NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_role (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- =============================================
-- 访客信息表
-- =============================================
CREATE TABLE visitor (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL COMMENT '访客姓名',
    phone           VARCHAR(20)  NOT NULL COMMENT '手机号',
    id_card         VARCHAR(20)  COMMENT '身份证号',
    company         VARCHAR(128) COMMENT '所属公司',
    email           VARCHAR(128) COMMENT '邮箱',
    photo_url       VARCHAR(512) COMMENT '照片URL',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_phone (phone),
    INDEX idx_id_card (id_card),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访客信息表';

-- =============================================
-- 预约表
-- =============================================
CREATE TABLE appointment (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_no  VARCHAR(32)  NOT NULL UNIQUE COMMENT '预约编号',
    visitor_id      BIGINT       NOT NULL COMMENT '访客ID',
    host_user_id    BIGINT       NOT NULL COMMENT '被访人ID',
    visit_reason    VARCHAR(512) NOT NULL COMMENT '来访事由',
    visit_start_time DATETIME    NOT NULL COMMENT '预计到访开始时间',
    visit_end_time  DATETIME     NOT NULL COMMENT '预计到访结束时间',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING_APPROVAL'
                    COMMENT '状态: PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED, CHECKED_IN, CHECKED_OUT, EXPIRED, RESCHEDULED',
    visitor_count   INT          NOT NULL DEFAULT 1 COMMENT '来访人数',
    remark          VARCHAR(512) COMMENT '备注',
    batch_import_id BIGINT       COMMENT '批量导入记录ID',
    created_by      BIGINT       NOT NULL COMMENT '创建人',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_appointment_no (appointment_no),
    INDEX idx_visitor_id (visitor_id),
    INDEX idx_host_user_id (host_user_id),
    INDEX idx_status (status),
    INDEX idx_visit_time (visit_start_time, visit_end_time),
    INDEX idx_created_by (created_by),
    FOREIGN KEY (visitor_id) REFERENCES visitor(id),
    FOREIGN KEY (host_user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约表';

-- =============================================
-- 审批记录表
-- =============================================
CREATE TABLE approval_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id  BIGINT       NOT NULL COMMENT '预约ID',
    approver_id     BIGINT       NOT NULL COMMENT '审批人ID',
    action          VARCHAR(20)  NOT NULL COMMENT '审批动作: APPROVE, REJECT',
    comment         VARCHAR(512) COMMENT '审批意见',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_appointment_id (appointment_id),
    INDEX idx_approver_id (approver_id),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    FOREIGN KEY (approver_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审批记录表';

-- =============================================
-- 通行码表
-- =============================================
CREATE TABLE pass_code (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id  BIGINT       NOT NULL COMMENT '预约ID',
    code            VARCHAR(128) NOT NULL UNIQUE COMMENT '通行码内容',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                    COMMENT '状态: ACTIVE, USED, EXPIRED, REVOKED',
    max_use_count   INT          NOT NULL DEFAULT 1 COMMENT '最大使用次数',
    used_count      INT          NOT NULL DEFAULT 0 COMMENT '已使用次数',
    valid_from      DATETIME     NOT NULL COMMENT '生效时间',
    valid_until     DATETIME     NOT NULL COMMENT '失效时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_code (code),
    INDEX idx_appointment_id (appointment_id),
    INDEX idx_status (status),
    INDEX idx_valid_until (valid_until),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通行码表';

-- =============================================
-- 签到签退记录表
-- =============================================
CREATE TABLE check_in_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id  BIGINT       NOT NULL COMMENT '预约ID',
    visitor_id      BIGINT       NOT NULL COMMENT '访客ID',
    pass_code_id    BIGINT       COMMENT '使用的通行码ID',
    check_in_time   DATETIME     COMMENT '签到时间',
    check_out_time  DATETIME     COMMENT '签退时间',
    gate_id         VARCHAR(64)  COMMENT '签到闸机ID',
    check_out_gate_id VARCHAR(64) COMMENT '签退闸机ID',
    status          VARCHAR(20)  NOT NULL DEFAULT 'CHECKED_IN'
                    COMMENT '状态: CHECKED_IN, CHECKED_OUT, ABNORMAL',
    remark          VARCHAR(512) COMMENT '备注',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_appointment_id (appointment_id),
    INDEX idx_visitor_id (visitor_id),
    INDEX idx_check_in_time (check_in_time),
    INDEX idx_status (status),
    FOREIGN KEY (appointment_id) REFERENCES appointment(id),
    FOREIGN KEY (visitor_id) REFERENCES visitor(id),
    FOREIGN KEY (pass_code_id) REFERENCES pass_code(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到签退记录表';

-- =============================================
-- 黑名单表
-- =============================================
CREATE TABLE blacklist (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    visitor_id      BIGINT       COMMENT '关联访客ID',
    name            VARCHAR(64)  NOT NULL COMMENT '姓名',
    id_card         VARCHAR(20)  COMMENT '身份证号',
    phone           VARCHAR(20)  COMMENT '手机号',
    reason          VARCHAR(512) NOT NULL COMMENT '拉黑原因',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 0-已移除, 1-生效中',
    created_by      BIGINT       NOT NULL COMMENT '操作人',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_visitor_id (visitor_id),
    INDEX idx_id_card (id_card),
    INDEX idx_phone (phone),
    INDEX idx_status (status),
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='黑名单表';

-- =============================================
-- 异常放行记录表
-- =============================================
CREATE TABLE abnormal_pass_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    visitor_name    VARCHAR(64)  COMMENT '访客姓名',
    visitor_phone   VARCHAR(20)  COMMENT '访客手机号',
    visitor_id_card VARCHAR(20)  COMMENT '身份证号',
    gate_id         VARCHAR(64)  COMMENT '闸机ID',
    reason          VARCHAR(512) NOT NULL COMMENT '异常原因',
    type            VARCHAR(32)  NOT NULL COMMENT '类型: NO_APPOINTMENT, EXPIRED, BLACKLISTED, INVALID_CODE, MANUAL_OVERRIDE',
    operator_id     BIGINT       COMMENT '操作人ID',
    handled         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已处理: 0-否, 1-是',
    handle_remark   VARCHAR(512) COMMENT '处理备注',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_type (type),
    INDEX idx_created_at (created_at),
    INDEX idx_handled (handled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异常放行记录表';

-- =============================================
-- 批量导入记录表
-- =============================================
CREATE TABLE batch_import_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_name       VARCHAR(256) NOT NULL COMMENT '导入文件名',
    total_count     INT          NOT NULL DEFAULT 0 COMMENT '总记录数',
    success_count   INT          NOT NULL DEFAULT 0 COMMENT '成功数',
    fail_count      INT          NOT NULL DEFAULT 0 COMMENT '失败数',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING'
                    COMMENT '状态: PROCESSING, COMPLETED, PARTIAL_FAIL, FAILED',
    error_detail    TEXT         COMMENT '错误详情JSON',
    created_by      BIGINT       NOT NULL COMMENT '导入人',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_created_by (created_by),
    FOREIGN KEY (created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量导入记录表';

-- =============================================
-- 通知消息表
-- =============================================
CREATE TABLE notification (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_user_id  BIGINT       NOT NULL COMMENT '接收人ID',
    type            VARCHAR(32)  NOT NULL COMMENT '类型: VISITOR_ARRIVAL, ABNORMAL_ALERT, APPROVAL_REMIND, APPOINTMENT_EXPIRED',
    title           VARCHAR(128) NOT NULL COMMENT '标题',
    content         TEXT         NOT NULL COMMENT '内容',
    is_read         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读: 0-否, 1-是',
    reference_id    BIGINT       COMMENT '关联业务ID',
    reference_type  VARCHAR(32)  COMMENT '关联业务类型: APPOINTMENT, CHECK_IN, ABNORMAL_PASS',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_target_user (target_user_id),
    INDEX idx_type (type),
    INDEX idx_is_read (is_read),
    FOREIGN KEY (target_user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知消息表';

-- =============================================
-- 初始数据
-- =============================================
INSERT INTO sys_role (role_code, role_name, description) VALUES
('ADMIN',      '系统管理员', '最高权限，可管理所有数据'),
('SUPERVISOR', '主管',       '可审批预约、管理下属访客记录'),
('SECURITY',   '安保人员',   '可处理签到签退、异常放行、黑名单管理'),
('EMPLOYEE',   '普通员工',   '可发起访客预约、查看自己的预约记录');

-- 管理员账号 密码: admin123
INSERT INTO sys_user (username, password, real_name, phone, department) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', '13800000000', '信息技术部');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

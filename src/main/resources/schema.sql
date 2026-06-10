-- ================================================================
-- Visitor Gate Management System - Database Schema
-- ================================================================

CREATE DATABASE IF NOT EXISTS visitor_gate DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE visitor_gate;

-- ----------------------------------------------------------------
-- 1. sys_user - System users (employees, security, admins)
-- ----------------------------------------------------------------
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    username    VARCHAR(50)  NOT NULL COMMENT 'Username',
    password    VARCHAR(200) NOT NULL COMMENT 'BCrypt encrypted password',
    real_name   VARCHAR(50)  NOT NULL COMMENT 'Real name',
    dept_name   VARCHAR(100) DEFAULT NULL COMMENT 'Department name',
    role        VARCHAR(20)  NOT NULL DEFAULT 'EMPLOYEE' COMMENT 'Role: EMPLOYEE, SECURITY, ADMIN',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT 'Phone number',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0=disabled, 1=enabled',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='System users';

-- ----------------------------------------------------------------
-- 2. visitor - Visitor information
-- ----------------------------------------------------------------
CREATE TABLE visitor (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    name        VARCHAR(50)  NOT NULL COMMENT 'Visitor name',
    id_card     VARCHAR(128) DEFAULT NULL COMMENT 'ID card number (AES encrypted)',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT 'Phone number',
    company     VARCHAR(100) DEFAULT NULL COMMENT 'Company name',
    photo_url   VARCHAR(500) DEFAULT NULL COMMENT 'Photo URL',
    visit_count INT          NOT NULL DEFAULT 0 COMMENT 'Total visit count',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_phone (phone),
    KEY idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Visitor information';

-- ----------------------------------------------------------------
-- 3. appointment - Visitor appointments
-- ----------------------------------------------------------------
CREATE TABLE appointment (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    appoint_no       VARCHAR(32)  NOT NULL COMMENT 'Appointment number',
    visitor_id       BIGINT       NOT NULL COMMENT 'Visitor ID',
    host_id          BIGINT       NOT NULL COMMENT 'Host employee ID',
    visit_type       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'Type: NORMAL, MEETING, TEMPORARY',
    purpose          VARCHAR(500) DEFAULT NULL COMMENT 'Visit purpose',
    expected_arrive  DATETIME     NOT NULL COMMENT 'Expected arrival time',
    expected_leave   DATETIME     DEFAULT NULL COMMENT 'Expected departure time',
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'Status: PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED, CHECKED_IN, COMPLETED',
    approved_by      BIGINT       DEFAULT NULL COMMENT 'Approver ID',
    approved_at      DATETIME     DEFAULT NULL COMMENT 'Approval time',
    reject_reason    VARCHAR(500) DEFAULT NULL COMMENT 'Rejection reason',
    reschedule_from  BIGINT       DEFAULT NULL COMMENT 'Rescheduled from appointment ID',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appoint_no (appoint_no),
    KEY idx_visitor (visitor_id),
    KEY idx_host (host_id),
    KEY idx_status (status),
    KEY idx_expected_arrive (expected_arrive)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Visitor appointments';

-- ----------------------------------------------------------------
-- 4. pass_code - Access pass codes (QR codes)
-- ----------------------------------------------------------------
CREATE TABLE pass_code (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    appointment_id  BIGINT       NOT NULL COMMENT 'Appointment ID (1:1)',
    code            VARCHAR(128) NOT NULL COMMENT 'Pass code content (UUID+HMAC)',
    qr_image        VARCHAR(500) DEFAULT NULL COMMENT 'QR code image path',
    max_uses        INT          NOT NULL DEFAULT 2 COMMENT 'Max usage count',
    used_count      INT          NOT NULL DEFAULT 0 COMMENT 'Used count',
    valid_from      DATETIME     NOT NULL COMMENT 'Valid from',
    valid_to        DATETIME     NOT NULL COMMENT 'Valid to',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'Status: ACTIVE, USED_UP, EXPIRED, REVOKED',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_appointment (appointment_id),
    UNIQUE KEY uk_code (code),
    KEY idx_status (status),
    KEY idx_valid_to (valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Access pass codes';

-- ----------------------------------------------------------------
-- 5. access_log - Gate access logs
-- ----------------------------------------------------------------
CREATE TABLE access_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    pass_code_id    BIGINT       DEFAULT NULL COMMENT 'Pass code ID',
    visitor_id      BIGINT       NOT NULL COMMENT 'Visitor ID',
    appointment_id  BIGINT       DEFAULT NULL COMMENT 'Appointment ID',
    action          VARCHAR(10)  NOT NULL COMMENT 'Action: ENTRY, EXIT',
    gate_location   VARCHAR(100) DEFAULT NULL COMMENT 'Gate location',
    result          VARCHAR(10)  NOT NULL DEFAULT 'PASS' COMMENT 'Result: PASS, DENIED, ANOMALY',
    deny_reason     VARCHAR(200) DEFAULT NULL COMMENT 'Denial reason',
    operator_id     BIGINT       DEFAULT NULL COMMENT 'Operator (security/system)',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_visitor (visitor_id),
    KEY idx_appointment (appointment_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Gate access logs';

-- ----------------------------------------------------------------
-- 6. blacklist - Blacklisted visitors
-- ----------------------------------------------------------------
CREATE TABLE blacklist (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    name        VARCHAR(50)  NOT NULL COMMENT 'Name',
    id_card     VARCHAR(128) DEFAULT NULL COMMENT 'ID card (AES encrypted)',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT 'Phone',
    reason      VARCHAR(500) DEFAULT NULL COMMENT 'Blacklist reason',
    operator_id BIGINT       NOT NULL COMMENT 'Operator ID',
    status      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'Status: ACTIVE, REMOVED',
    expire_at   DATETIME     DEFAULT NULL COMMENT 'Expiry (NULL=permanent)',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Blacklist';

-- ----------------------------------------------------------------
-- 7. approval_record - Approval records
-- ----------------------------------------------------------------
CREATE TABLE approval_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    appointment_id  BIGINT       NOT NULL COMMENT 'Appointment ID',
    approver_id     BIGINT       NOT NULL COMMENT 'Approver ID',
    action          VARCHAR(10)  NOT NULL COMMENT 'Action: APPROVE, REJECT',
    remark          VARCHAR(500) DEFAULT NULL COMMENT 'Remark',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_appointment (appointment_id),
    KEY idx_approver (approver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Approval records';

-- ----------------------------------------------------------------
-- 8. anomaly_record - Anomaly access records
-- ----------------------------------------------------------------
CREATE TABLE anomaly_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    visitor_id      BIGINT       NOT NULL COMMENT 'Visitor ID',
    appointment_id  BIGINT       DEFAULT NULL COMMENT 'Appointment ID (nullable)',
    anomaly_type    VARCHAR(30)  NOT NULL COMMENT 'Type: OVERSTAY, NO_DEPARTURE, EXPIRED_PASS, DUPLICATE_ENTRY, BLACKLIST_ATTEMPT, UNAUTHORIZED_AREA',
    description     VARCHAR(500) DEFAULT NULL COMMENT 'Description',
    security_id     BIGINT       DEFAULT NULL COMMENT 'Handling security staff',
    handle_result   VARCHAR(500) DEFAULT NULL COMMENT 'Handling result',
    status          VARCHAR(10)  NOT NULL DEFAULT 'OPEN' COMMENT 'Status: OPEN, HANDLING, RESOLVED',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_visitor (visitor_id),
    KEY idx_status (status),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Anomaly access records';

-- ----------------------------------------------------------------
-- 9. import_batch - Batch import records
-- ----------------------------------------------------------------
CREATE TABLE import_batch (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    operator_id    BIGINT       NOT NULL COMMENT 'Operator ID',
    total_count    INT          NOT NULL DEFAULT 0 COMMENT 'Total records',
    success_count  INT          NOT NULL DEFAULT 0 COMMENT 'Success count',
    fail_count     INT          NOT NULL DEFAULT 0 COMMENT 'Failure count',
    fail_detail    JSON         DEFAULT NULL COMMENT 'Failure details JSON',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING' COMMENT 'Status: PROCESSING, COMPLETED, PARTIAL_FAIL, FAILED',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_operator (operator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Batch import records';

-- ----------------------------------------------------------------
-- 10. area - Park areas / buildings / zones
-- ----------------------------------------------------------------
CREATE TABLE area (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    name        VARCHAR(100) NOT NULL COMMENT 'Area name',
    building    VARCHAR(100) DEFAULT NULL COMMENT 'Building name',
    description VARCHAR(500) DEFAULT NULL COMMENT 'Description',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0=disabled, 1=enabled',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Park areas';

-- ----------------------------------------------------------------
-- 11. gate - Physical gate terminals
-- ----------------------------------------------------------------
CREATE TABLE gate (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    name          VARCHAR(100) NOT NULL COMMENT 'Gate name',
    area_id       BIGINT       NOT NULL COMMENT 'Area ID',
    location_desc VARCHAR(200) DEFAULT NULL COMMENT 'Location description',
    gate_type     VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'Type: NORMAL, ENTRY_ONLY, EXIT_ONLY',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '0=disabled, 1=enabled',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_area (area_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Physical gate terminals';

-- ----------------------------------------------------------------
-- 12. area_authorization - Per-appointment area access grants
-- ----------------------------------------------------------------
CREATE TABLE area_authorization (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    appointment_id  BIGINT   NOT NULL COMMENT 'Appointment ID',
    area_id         BIGINT   NOT NULL COMMENT 'Area ID',
    valid_from      DATETIME NOT NULL COMMENT 'Valid from',
    valid_to        DATETIME NOT NULL COMMENT 'Valid to',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_appointment (appointment_id),
    KEY idx_area (area_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Area access authorizations';

-- ----------------------------------------------------------------
-- 13. Alter existing tables for multi-gate support
-- ----------------------------------------------------------------
ALTER TABLE access_log ADD COLUMN gate_id BIGINT DEFAULT NULL COMMENT 'Gate ID' AFTER operator_id;
ALTER TABLE access_log ADD COLUMN area_id BIGINT DEFAULT NULL COMMENT 'Area ID' AFTER gate_id;

ALTER TABLE appointment ADD COLUMN max_companions INT NOT NULL DEFAULT 0 COMMENT 'Max allowed companions' AFTER reschedule_from;

ALTER TABLE anomaly_record ADD COLUMN gate_id BIGINT DEFAULT NULL COMMENT 'Gate ID' AFTER appointment_id;
ALTER TABLE anomaly_record ADD COLUMN area_id BIGINT DEFAULT NULL COMMENT 'Area ID' AFTER gate_id;

-- ----------------------------------------------------------------
-- Seed data: areas and gates
-- ----------------------------------------------------------------
INSERT INTO area (name, building, description, status) VALUES
('A栋办公区', 'A栋', 'A栋办公楼1-5层', 1),
('B栋会议区', 'B栋', 'B栋会议中心', 1);

INSERT INTO gate (name, area_id, location_desc, gate_type, status) VALUES
('A栋正门', 1, 'A栋办公楼正门入口', 'NORMAL', 1),
('B栋正门', 2, 'B栋会议中心正门', 'NORMAL', 1);

-- ----------------------------------------------------------------
-- Initial data: admin user (password: admin123)
-- ----------------------------------------------------------------
INSERT INTO sys_user (username, password, real_name, dept_name, role, phone, status)
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'System Admin', 'IT', 'ADMIN', '13800000000', 1);

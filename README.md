# 访客预约与门禁联动后端服务

园区访客预约、审批、通行码、签到签退、黑名单拦截与异常放行管理的后端服务系统。

## 技术栈

- Java 17 / Spring Boot 3.2.5
- Spring Security + JWT 认证
- MyBatis + MySQL
- Redis (缓存 + 分布式锁)
- WebSocket (实时推送)
- Spring Scheduler (定时任务)

## 快速启动

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6.0+

### 1. 数据库初始化

```bash
mysql -u root -p < sql/init.sql
```

### 2. 配置

修改 `src/main/resources/application.yml` 中的数据库和 Redis 连接信息，或通过环境变量：

```bash
export DB_USERNAME=root
export DB_PASSWORD=your_password
export REDIS_HOST=localhost
export JWT_SECRET=your-jwt-secret-at-least-256-bits
export PASSCODE_SECRET=your-passcode-hmac-secret
```

### 3. 编译运行

```bash
mvn clean compile
mvn spring-boot:run
```

### 4. 运行测试

```bash
mvn test
```

## 默认账号

| 用户名 | 密码 | 角色 |
|-------|------|------|
| admin | admin123 | ADMIN |

## 系统架构

```
┌─────────────┐     ┌──────────────┐     ┌───────────────┐
│   前端/门禁   │────>│  Controller  │────>│    Service     │
└─────────────┘     └──────────────┘     └───────┬───────┘
                           │                      │
                    ┌──────┴──────┐         ┌─────┴─────┐
                    │   Security  │         │  MyBatis   │
                    │  JWT Filter │         │  Mapper    │
                    └─────────────┘         └─────┬─────┘
                                                   │
┌─────────────┐     ┌──────────────┐         ┌────┴────┐
│  WebSocket  │<────│ Notification │         │  MySQL  │
│   推送       │     │   Service    │         └─────────┘
└─────────────┘     └──────────────┘
                                              ┌─────────┐
┌─────────────┐     ┌──────────────┐         │  Redis   │
│  定时任务    │────>│  Scheduler   │────────>│  缓存/锁  │
└─────────────┘     └──────────────┘         └─────────┘
```

## 核心功能

### 预约状态机

```
PENDING_APPROVAL → APPROVED → CHECKED_IN → CHECKED_OUT
       ↓              ↓
    REJECTED      EXPIRED / CANCELLED / RESCHEDULED
```

### 角色权限 (RBAC)

| 角色 | 权限范围 |
|------|---------|
| EMPLOYEE | 创建/查看自己的预约，批量导入 |
| SUPERVISOR | 审批同部门预约，查看部门内记录 |
| SECURITY | 签到签退，黑名单管理，异常放行，审批 |
| ADMIN | 所有操作 |

### 通行码机制

- HMAC-SHA256 签名防篡改
- 有效期绑定预约时间（支持提前30分钟入场）
- 使用次数限制（默认2次）
- Redis 分布式锁 + DB 乐观锁防并发重复使用

### WebSocket 推送

连接: `ws://host:port/ws/notify?token={JWT_TOKEN}`

消息类型：
- `VISITOR_ARRIVAL` - 访客到达通知（推给被访员工）
- `ABNORMAL_ALERT` - 异常通行告警（推给安保）
- `APPROVAL_REMIND` - 审批提醒（推给主管/安保）
- `APPOINTMENT_EXPIRED` - 预约过期通知（推给发起人）

### 定时任务

- 每5分钟：过期预约自动处理 + 通行码失效
- 每10分钟：超时未离场访客告警

## API 接口概览

### 认证

```
POST   /api/v1/auth/login          登录
POST   /api/v1/auth/logout         登出
GET    /api/v1/auth/me             当前用户信息
```

### 预约管理

```
POST   /api/v1/appointments             创建预约
GET    /api/v1/appointments              预约列表（分页、权限过滤）
GET    /api/v1/appointments/{id}         预约详情
PUT    /api/v1/appointments/{id}         修改预约（仅待审批状态）
POST   /api/v1/appointments/{id}/cancel  取消预约
POST   /api/v1/appointments/{id}/reschedule  预约改期
GET    /api/v1/appointments/check-duplicate  重复预约检查
```

### 审批

```
GET    /api/v1/approvals/pending                  待审批列表
POST   /api/v1/approvals/{appointmentId}/approve   通过
POST   /api/v1/approvals/{appointmentId}/reject    拒绝
GET    /api/v1/approvals/history                   审批历史
```

### 访客

```
POST   /api/v1/visitors            登记访客
GET    /api/v1/visitors             访客列表
GET    /api/v1/visitors/{id}       访客详情
PUT    /api/v1/visitors/{id}       更新信息
GET    /api/v1/visitors/search     搜索
```

### 通行码

```
GET    /api/v1/pass-codes/appointment/{id}   获取预约通行码
POST   /api/v1/pass-codes/verify             校验通行码（门禁调用）
POST   /api/v1/pass-codes/{id}/revoke        撤销通行码
```

### 签到签退

```
POST   /api/v1/check-in             访客签到
POST   /api/v1/check-out            访客签退
GET    /api/v1/check-in/current     在场访客列表
GET    /api/v1/check-in/records     签到记录查询
```

### 黑名单

```
POST   /api/v1/blacklist            添加黑名单
GET    /api/v1/blacklist            黑名单列表
DELETE /api/v1/blacklist/{id}       移除（仅ADMIN）
GET    /api/v1/blacklist/check      黑名单检查
```

### 异常放行

```
POST   /api/v1/abnormal-pass              记录异常放行
GET    /api/v1/abnormal-pass               异常记录列表
POST   /api/v1/abnormal-pass/{id}/handle  处理异常记录
```

### 批量导入

```
POST   /api/v1/batch-import/upload        上传Excel
GET    /api/v1/batch-import/{id}/status   导入状态
GET    /api/v1/batch-import/{id}/errors   错误详情
GET    /api/v1/batch-import/template      下载模板
```

### 仪表盘

```
GET    /api/v1/dashboard/stats     统计概览
```

## 数据库表

| 表名 | 说明 |
|------|------|
| sys_user | 系统用户 |
| sys_role | 角色 |
| sys_user_role | 用户角色关联 |
| visitor | 访客信息 |
| appointment | 预约 |
| approval_record | 审批记录 |
| pass_code | 通行码 |
| check_in_record | 签到签退记录 |
| blacklist | 黑名单 |
| abnormal_pass_record | 异常放行记录 |
| batch_import_record | 批量导入记录 |
| notification | 通知消息 |

## 项目结构

```
src/main/java/com/visitor/
├── VisitorManagementApplication.java
├── common/          常量、异常、响应体、工具类
├── config/          Security、Redis、WebSocket、定时任务配置
├── security/        JWT 过滤器、认证入口、UserDetailsService
├── entity/          数据库实体
├── dto/             请求和响应 DTO
├── mapper/          MyBatis Mapper 接口
├── service/         业务逻辑（接口+实现）
├── controller/      REST 接口
├── websocket/       WebSocket 处理器和会话管理
└── scheduler/       定时任务
```

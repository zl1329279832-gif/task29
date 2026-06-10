# Visitor Gate - 访客预约与门禁联动后端服务

园区访客全生命周期管理系统，覆盖预约、审批、二维码通行、签到、离场、黑名单拦截、批量导入和异常放行。

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.2.5 |
| 安全 | Spring Security + JWT (jjwt) | 6.x / 0.12.5 |
| ORM | MyBatis-Plus | 3.5.5 |
| 数据库 | MySQL | 8.0+ |
| 缓存 | Redis (Lettuce) | 3.x |
| 推送 | WebSocket | - |
| 二维码 | ZXing | 3.5.3 |
| 定时任务 | Spring Scheduling | - |

## 项目结构

```
src/main/java/com/visitor/
├── VisitorApplication.java          # 启动类
├── config/                           # 配置 (Security, Redis, WebSocket)
├── security/                         # JWT 认证组件
├── controller/                       # REST API 控制器 (8个)
├── service/                          # 业务逻辑层 (9个服务)
├── model/
│   ├── entity/                       # 数据库实体 (9张表)
│   ├── enums/                        # 状态枚举 (11个)
│   ├── dto/                          # 请求 DTO (11个)
│   └── vo/                           # 响应 VO (6个)
├── mapper/                           # MyBatis Mapper 接口 (9个)
├── exception/                        # 全局异常处理
├── websocket/                        # WebSocket 推送处理器
├── task/                             # 定时任务
└── util/                             # 工具类 (二维码、分布式锁)
```

## 快速启动

### 前置条件

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.8+

### 1. 初始化数据库

```bash
mysql -u root -p < src/main/resources/schema.sql
```

### 2. 修改配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/visitor_gate
    username: root
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379

visitor:
  jwt:
    secret: your-256-bit-secret-key
```

### 3. 启动服务

```bash
mvn spring-boot:run
```

服务启动后访问 `http://localhost:8080`

### 4. 运行测试

```bash
mvn test
```

## 数据库设计 (9张表)

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `sys_user` | 系统用户 | role(EMPLOYEE/SECURITY/ADMIN) |
| `visitor` | 访客信息 | name, id_card, phone, visit_count |
| `appointment` | 预约单 | status(7种状态), visit_type |
| `pass_code` | 通行码 | code(HMAC签名), max_uses, valid_from/to |
| `access_log` | 通行记录 | action(ENTRY/EXIT), result(PASS/DENIED/ANOMALY) |
| `blacklist` | 黑名单 | status, expire_at |
| `approval_record` | 审批记录 | action(APPROVE/REJECT) |
| `anomaly_record` | 异常记录 | anomaly_type(6种), status |
| `import_batch` | 批量导入 | success/fail_count, fail_detail(JSON) |

## 状态机

### 预约状态

```
PENDING → APPROVED → CHECKED_IN → COMPLETED
  │          │
  │          ├→ REJECTED
  │          └→ EXPIRED (定时任务)
  └→ CANCELLED
APPROVED → CANCELLED (改期)
```

### 通行码状态

```
ACTIVE → USED_UP (次数耗尽)
ACTIVE → EXPIRED (过期)
ACTIVE → REVOKED (撤销)
```

## API 接口文档

### 认证

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/auth/login` | 登录获取JWT | 公开 |
| POST | `/api/auth/refresh` | 刷新Token | 公开 |

### 访客管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/visitors` | 登记访客 | 认证 |
| GET | `/api/visitors` | 查询访客列表 | 认证 |
| GET | `/api/visitors/{id}` | 访客详情 | 认证 |

### 预约管理

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/appointments` | 创建预约 | EMPLOYEE+ |
| GET | `/api/appointments` | 预约列表(按权限过滤) | 认证 |
| GET | `/api/appointments/{id}` | 预约详情 | 认证(仅本人) |
| PUT | `/api/appointments/{id}/reschedule` | 改期 | EMPLOYEE(仅本人) |
| PUT | `/api/appointments/{id}/cancel` | 取消 | EMPLOYEE(仅本人) |

### 审批

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/approvals/{id}/approve` | 通过 | ADMIN |
| POST | `/api/approvals/{id}/reject` | 拒绝 | ADMIN |

### 通行码

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| GET | `/api/pass-codes/{appointmentId}` | 获取通行码(含二维码) | 认证 |
| POST | `/api/pass-codes/verify` | 扫码校验 | 认证 |

### 门禁

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/gate/checkin` | 签到(扫码) | 认证 |
| POST | `/api/gate/checkout` | 离场 | 认证 |
| GET | `/api/gate/logs` | 通行记录 | 认证 |
| POST | `/api/gate/anomaly-release` | 异常放行 | SECURITY/ADMIN |

### 黑名单

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/blacklist` | 添加黑名单 | ADMIN |
| GET | `/api/blacklist` | 查询黑名单 | 认证 |
| POST | `/api/blacklist/check` | 校验黑名单 | 认证 |
| DELETE | `/api/blacklist/{id}` | 移除黑名单 | ADMIN |

### 批量导入

| 方法 | 路径 | 说明 | 权限 |
|------|------|------|------|
| POST | `/api/import/meeting-visitors` | 批量导入会议访客 | EMPLOYEE/ADMIN |
| GET | `/api/import/{batchId}/status` | 导入状态查询 | 认证 |

### WebSocket

连接地址：`ws://localhost:8080/ws/visitor?userId=xxx&role=xxx`

推送事件类型：
- `VISITOR_ARRIVED` - 访客到达(推送给接待员工)
- `ANOMALY_ALERT` - 异常通行(推送给安保)
- `BLACKLIST_ALERT` - 黑名单拦截(推送给安保)
- `APPROVAL_REMINDER` - 审批提醒(推送给管理员)
- `APPROVAL_RESULT` - 审批结果(推送给员工)
- `UNDEPARTED_WARNING` - 未离场警告(推送给安保)

## 核心业务逻辑

### 通行码生成与校验

1. **生成**：审批通过后自动生成，码内容为 `UUID + HMAC-SHA256签名`，防伪造
2. **缓存**：Redis 缓存通行码状态，扫码先查 Redis 再查 DB
3. **四重校验**：有效期 + 使用次数 + 预约状态 + 黑名单
4. **防重复扫码**：Redis 分布式锁，防止并发扫码导致多次计数

### 黑名单校验点

- 预约创建时
- 通行码签到时
- 批量导入时

命中黑名单 → 拒绝操作 + 创建异常记录 + WebSocket推送安保

### 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| 重复预约 | 同访客+同时段+同接待人检测，返回409 |
| 二维码重复使用 | Redis分布式锁 + used_count原子递增 |
| 访客未离场 | 定时任务(5分钟)检测超时，推送安保 |
| 预约改期 | 旧预约CANCELLED + 通行码REVOKED + 新预约重新审批 |
| 批量导入部分失败 | 逐行独立处理，汇总成功/失败+失败明细 |
| 员工越权查看 | Service层按host_id过滤，EMPLOYEE只看自己的预约 |
| 预约过期 | 定时任务(1分钟)自动过期，同步过期通行码 |

### 权限模型

| 角色 | 权限范围 |
|------|---------|
| EMPLOYEE | 创建/查看自己的预约，登记访客 |
| SECURITY | 门禁操作，查看通行记录，处理异常，异常放行 |
| ADMIN | 全部权限 + 审批 + 黑名单管理 |

## 定时任务

| 频率 | 任务 | 说明 |
|------|------|------|
| 每1分钟 | `expireOverdueItems` | 过期超时预约和通行码 |
| 每5分钟 | `detectUndepartedVisitors` | 检测已签到但未离场访客，推送警告 |

## 测试覆盖

| 测试类 | 覆盖场景 |
|--------|---------|
| `QrCodeUtilTest` | 通行码生成、HMAC签名校验、二维码生成、唯一性 |
| `AppointmentServiceTest` | 创建、重复检测、黑名单、审批、拒绝、取消、改期、签到、完成、过期 |
| `PassCodeServiceTest` | 生成、已存在、扫码校验、无效签名、重复扫码、过期、用完、撤销、递增到上限 |
| `GateServiceTest` | 签到成功、黑名单拦截、离场成功、已离场、未签到 |
| `BlacklistServiceTest` | 检查、断言、添加、重复添加、移除 |
| `ImportServiceTest` | 全部成功、部分失败、全部失败、黑名单、空数据 |

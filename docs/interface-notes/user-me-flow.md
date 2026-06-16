# GET /user/me 接口流程梳理

> 本文档描述"获取当前登录用户信息"接口从 HTTP 请求到返回响应的完整调用链路。

---

## 一、接口概览

| 项目 | 说明 |
|---|---|
| 请求方法 | `GET` |
| 路径 | `/user/me` |
| 鉴权 | 需要登录（Sa-Token Token 校验） |
| 权限 | 所有已登录用户（无角色限制） |
| 响应类型 | `application/json` |
| 响应体 | `Result<CurrentUserVO>` |

---

## 二、请求全链路

```
HTTP GET /user/me
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  SaInterceptor（拦截器 #1）                           │
│  调用 StpUtil.checkLogin()                           │
│  → Token 无效 / 未登录：抛异常，返回 401              │
│  → Token 有效：放行，loginId 存入 SaToken ThreadLocal │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  DemoModeInterceptor（拦截器 #2，体验环境专用）        │
│  只读模式下拦截写操作；GET /user/me 为读操作，直接放行  │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  UserContextInterceptor.preHandle()（拦截器 #3）      │
│                                                      │
│  1. 跳过 ASYNC / OPTIONS 请求                        │
│  2. StpUtil.getLoginIdAsString()                     │
│     → 从 SaToken 上下文中取当前用户 ID（字符串）       │
│  3. userMapper.selectById(loginId)                   │
│     → 查 user 表，获取 username / role / avatar 字段  │
│  4. UserContext.set(LoginUser{...})                  │
│     → 将用户信息封装后写入 TTL 线程上下文              │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  UserController.currentUser()                        │
│                                                      │
│  1. UserContext.requireUser()                        │
│     → 从 TTL 线程上下文中读取 LoginUser               │
│     → 若为 null 则抛 ClientException（理论不触发）     │
│  2. new CurrentUserVO(userId, username, role, avatar) │
│     → 将 LoginUser 字段直接映射到 VO，无额外计算       │
│  3. Results.success(vo)                              │
│     → 包装为 Result<CurrentUserVO>，code="0"         │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│  UserContextInterceptor.afterCompletion()            │
│  UserContext.clear()                                 │
│  → 从 TTL 中移除当前用户，防止线程复用时数据污染       │
└──────────────────────────────────────────────────────┘
                       │
                       ▼
HTTP 响应 200 OK
{
  "code": "0",
  "data": {
    "userId": "1234567890",
    "username": "alice",
    "role": "user",
    "avatar": "https://..."
  }
}
```

---

## 三、核心组件说明

### 3.1 Sa-Token 登录校验（SaInterceptor）

- 框架：`cn.dev33:sa-token-spring-boot3-starter`
- 校验方式：读取请求头 `Authorization`（或 Cookie）中的 Token，调用 `StpUtil.checkLogin()`
- Token 有效时，loginId 存入 Sa-Token 自身的 ThreadLocal，供后续拦截器使用
- 例外路径：`/auth/**`、`/error`、ASYNC 请求、OPTIONS 预检请求

### 3.2 用户上下文填充（UserContextInterceptor）

- 触发时机：SaInterceptor 放行后，进入 Controller 前
- 每次请求执行一次 DB 查询（`userMapper.selectById`），将最新用户信息写入线程上下文
- 使用 `TransmittableThreadLocal`（TTL）而非普通 `ThreadLocal`，确保异步线程池中也能传播用户信息
- `afterCompletion` 中调用 `UserContext.clear()` 清理，防止线程池复用时的"用户串号"问题

### 3.3 UserContext

```java
// TTL 线程上下文，全局静态访问
UserContext.set(loginUser);      // 拦截器写入
UserContext.requireUser();       // Controller 读取（null 时抛异常）
UserContext.getUserId();         // 业务代码快捷读 userId
UserContext.clear();             // 拦截器 afterCompletion 清理
```

> 与普通 `ThreadLocal` 的区别：TTL 可以跨线程传播（子线程 / 线程池），是 RAG 链路异步处理中正确获取当前用户的关键。

### 3.4 CurrentUserVO

`/user/me` 的响应 VO，字段与 `LoginUser` 一一对应：

| 字段 | 来源 | 说明 |
|---|---|---|
| `userId` | `user.id`（雪花 ID） | 用户唯一标识 |
| `username` | `user.username` | 登录用户名 |
| `role` | `user.role` | 角色（`admin` / `user`） |
| `avatar` | `user.avatar` | 头像 URL；为空时返回 GitHub 默认头像 |

---

## 四、统一响应格式

所有接口通过 `Results.success(data)` 包装返回 `Result<T>`：

| 字段 | 说明 |
|---|---|
| `code` | `"0"` 表示成功，其他值为错误码 |
| `message` | 成功时为 null，失败时为错误描述 |
| `data` | 业务数据（本接口为 `CurrentUserVO`） |
| `requestId` | 链路追踪 ID（由全局过滤器写入） |

---

## 五、异常场景

| 场景 | 触发位置 | 响应 |
|---|---|---|
| Token 不存在 / 已过期 | `SaInterceptor.checkLogin()` | Sa-Token 抛出 `NotLoginException`，全局异常处理器返回 401 |
| 用户 ID 在 DB 中不存在 | `UserContextInterceptor.preHandle()` | `userMapper.selectById` 返回 null，后续 `NPE` 或框架统一异常处理 |
| UserContext 未设置 | `UserContext.requireUser()` | 抛 `ClientException("未获取到当前登录用户")`（正常链路不触发） |

---

## 六、涉及核心类索引

| 类名 | 模块 | 职责 |
|---|---|---|
| `UserController` | `bootstrap/user/controller/` | HTTP 入口，读 UserContext 直接返回 VO |
| `CurrentUserVO` | `bootstrap/user/controller/vo/` | `/user/me` 响应 VO（userId/username/role/avatar） |
| `UserContextInterceptor` | `bootstrap/user/config/` | 每次请求前从 DB 加载用户信息并写入 TTL 上下文 |
| `SaTokenConfig` | `bootstrap/user/config/` | 注册 SaInterceptor + UserContextInterceptor 拦截链 |
| `UserContext` | `framework/context/` | TTL 线程上下文，全局存取当前登录用户 |
| `LoginUser` | `framework/context/` | 线程上下文中的用户快照（userId/username/role/avatar） |
| `Result<T>` | `framework/convention/` | 统一 API 响应包装（code/message/data/requestId） |
| `Results` | `framework/web/` | `Result` 构建工厂（`success()` / `failure()` 静态方法） |

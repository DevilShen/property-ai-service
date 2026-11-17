# 物业 AI 智能客服 · Java 后端（P1 骨架）

> Java 21 + Spring Boot 3 · 模块化单体（bff + domain）  
> 替代 W1 的 Node/Fastify 临时 mock；正式后端从本目录开始。

## 运行

```powershell
# 在仓库根目录
apps\server-java\mvnw.cmd -f apps/server-java/pom.xml spring-boot:run
```

或使用已安装 Maven：

```powershell
mvn -f apps/server-java/pom.xml spring-boot:run
```

健康检查：

```text
http://localhost:4000/healthz
```

## 目录

```text
src/main/java/com/property/ai/
├─ PropertyAiApplication.java
├─ bff/          # 对前端的 REST/SSE 契约、鉴权、限流
│  ├─ health/
│  └─ config/
└─ domain/       # 领域能力（P1 先占位，W3 起按域填充）
```

## 约束

- 业务主数据不落 AI 侧；ERP 为唯一真相源。
- P1 只做 `/healthz`，12 屏 mock API 在 P1-030 用 Java Controller 实现。
- 前端仍走 REST，不依赖 Java 内部结构。

# 后端环境配置分离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Spring Boot 后端以 `dev` 或 `prod` Profile 安全地读取本地或服务器环境配置。

**Architecture:** `application.yml` 只保存公共业务配置和环境变量占位；`application-dev.yml` 与 `application-prod.yml` 保存环境差异。开发私密值保存在 Git 忽略的 `application-local.yml`，生产私密值由 systemd 的 `/etc/mayue-oj/app.env` 注入。

**Tech Stack:** Spring Boot 3.5、YAML、systemd、Maven/JUnit 5。

**Spec:** `docs/superpowers/specs/2026-11-11-backend-profile-config-design.md`

## Global Constraints

- 真实密码、密钥、Token、API Key 不得进入任何受 Git 跟踪的文件。
- 本地以 `--spring.profiles.active=dev` 启动；服务器以 `--spring.profiles.active=prod` 启动。
- 生产 Nginx 反代 `/api` 到回环地址 `127.0.0.1:8888`，不直接公开后端端口。
- 生产默认关闭本地 ComfyUI 与 Vision AI，允许通过环境变量显式开启。

## Review Focus

- 未激活 Profile 时不应隐式加载本地私密配置；由测试验证 `application.yml` 不包含 `spring.config.import`。
- `prod` 文件不得含局域网开发地址；由测试拒绝 `192.168.`。
- 受跟踪 YAML 不得含真实凭据或弱默认密码；由测试拒绝邮件密码和开发默认值。
- `dev` 必须可覆盖本地私密值；由测试验证其导入 `application-local.yml`。
- 生产环境禁用重资源本地服务；由测试验证 ComfyUI、Vision AI 默认关闭。

---

### Task 1: 配置契约测试

**Files:**
- Create: `spingboot-init/src/test/java/com/spingbootinit/config/ProfileConfigContractTest.java`
- Modify: `spingboot-init/pom.xml`（仅在缺少测试运行所需 Spring YAML 类时）

**Interfaces:**
- Consumes: 类路径下的 `application.yml`、`application-dev.yml`、`application-prod.yml`。
- Produces: 配置文件的自动化安全与 Profile 行为验证。

- [ ] **Step 1: 写入失败的配置契约测试**

断言公共配置不导入 `application-local.yml`；`dev` 导入该私密覆盖；`prod` 无 `192.168.`、无真实邮件口令、无 `123456` 或 `ChangeMe-DevOnly`；`prod` 默认关闭 Vision AI 与图像生成。

- [ ] **Step 2: 运行测试并确认失败**

Run: `mvn -q -Dtest=ProfileConfigContractTest test`

Expected: FAIL，现有公共配置仍导入本地私密文件且含开发默认配置。

- [ ] **Step 3: 保留测试所需的最小 YAML 读取实现**

测试使用 Spring 已有 YAML 资源读取能力，不引入新的运行时依赖。

- [ ] **Step 4: 在配置改造完成后运行测试**

Run: `mvn -q -Dtest=ProfileConfigContractTest test`

Expected: PASS。

### Task 2: 分离公共、开发与生产配置

**Files:**
- Modify: `spingboot-init/src/main/resources/application.yml`
- Modify: `spingboot-init/src/main/resources/application-dev.yml`
- Modify: `spingboot-init/src/main/resources/application-prod.yml`
- Modify: `spingboot-init/src/main/resources/application-local.yml.example`

**Interfaces:**
- Consumes: 环境变量 `MYSQL_*`、`REDIS_*`、`MAIL_*`、`JWT_SECRET`、`MINIO_*`、`SANDBOX_*`、`VISION_AI_*`、`IMAGE_GENERATION_*`、`BAILIAN_*`。
- Produces: `dev` 和 `prod` 两套可选择、无真实凭据的 Spring Boot 配置。

- [ ] **Step 1: 将公共配置收敛到 `application.yml`**

保留上传限制、Jackson、MyBatis、接口端口/前缀、判题与业务规则；删除本地私密文件自动导入、开发 IP、真实邮件凭据和弱默认秘密。

- [ ] **Step 2: 完善 `application-dev.yml`**

导入可选的 `application-local.yml`；对本机 MySQL、Redis、Sandbox、MinIO、Vision AI、ComfyUI 使用回环地址和环境变量占位。

- [ ] **Step 3: 完善 `application-prod.yml`**

为同机 MySQL、Redis、Sandbox、MinIO 声明回环地址和必填环境变量；默认关闭 Vision AI、ComfyUI、百炼服务，保留环境变量开启方式。

- [ ] **Step 4: 更新本地私密配置模板**

使 `application-local.yml.example` 只包含开发 Profile 需要覆盖的私密值及明确占位符。

- [ ] **Step 5: 运行契约测试与 Maven 构建**

Run: `mvn -q -Dtest=ProfileConfigContractTest test`，然后 `mvn -q -DskipTests package`

Expected: 两个命令均成功。

### Task 3: 服务器环境文件与启动说明

**Files:**
- Create: `deploy/ubuntu/mayue-oj.env.example`
- Create: `deploy/ubuntu/mayue-oj.service`
- Modify: `deploy/ubuntu/README.md`

**Interfaces:**
- Consumes: `/etc/mayue-oj/app.env` 与构建后的 Spring Boot JAR。
- Produces: 可复制的服务器密钥文件模板、systemd 服务定义和部署步骤。

- [ ] **Step 1: 新增生产环境变量模板**

模板仅包含变量名与安全占位符；不包含真实公网 IP、密码或 API Key。生产默认关闭 `VISION_AI_ENABLED`、`IMAGE_GENERATION_ENABLED`、`BAILIAN_ENABLED`。

- [ ] **Step 2: 新增 systemd 服务文件**

使用 `EnvironmentFile=/etc/mayue-oj/app.env` 与 `--spring.profiles.active=prod` 启动 JAR，并以 `Restart=on-failure` 保持进程可恢复。

- [ ] **Step 3: 更新 Ubuntu 部署说明**

写明复制模板、设置目录权限、生成强密钥、启用 systemd 服务、验证 `curl http://127.0.0.1:8888/api/doc.html` 的顺序；说明 Nginx 再反代 `/api`。

- [ ] **Step 4: 验证不跟踪服务器私密文件**

Run: `git check-ignore /etc/mayue-oj/app.env` 不适用于仓库路径；改为验证模板没有真实值，并检查 `.gitignore` 继续忽略 `application-local.yml` 与 `.env`。

- [ ] **Step 5: 运行完整后端测试**

Run: `mvn -q test`

Expected: PASS；若现有不相关测试失败，记录其失败名称和原因，不将其误报为配置改造失败。

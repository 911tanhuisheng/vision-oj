# 后端本地与生产环境配置设计

## 目标

让同一套 Spring Boot 后端在本地开发和 Ubuntu 服务器部署时，通过明确的 Profile 选择不同的非敏感配置；真实密码、密钥和 API Key 不进入 Git 仓库。

## 方案比较与选择

| 方案 | 说明 | 结果 |
|---|---|---|
| A：Profile 加环境变量 | 用 `dev` / `prod` 区分非敏感配置，真实密钥放本机私密文件或服务器环境文件 | 已选择 |
| B：手工改同一个配置文件 | 每次部署前直接修改地址、密码和开关 | 未选择，容易误带本地地址或泄露密钥 |

采用 Spring Profile 加环境变量：

- 本地启动：`dev`
- 服务器启动：`prod`
- 公共非环境配置留在 `application.yml`
- 本地私密覆盖保留在已忽略的 `application-local.yml`
- 服务器私密值由 systemd 的 `/etc/mayue-oj/app.env` 提供

## 配置边界

| 文件 | 用途 | 是否提交 Git |
|---|---|---|
| `application.yml` | 公共业务配置与安全的变量占位 | 是 |
| `application-dev.yml` | 本地服务地址、开发开关、加载本地私密覆盖 | 是 |
| `application-prod.yml` | 服务器服务地址、生产开关 | 是 |
| `application-local.yml` | 本地数据库密码、JWT、Sandbox 密钥、AI Key | 否 |
| `/etc/mayue-oj/app.env` | 生产数据库密码、JWT、Sandbox 密钥、AI Key | 否 |

## 行为

1. 公共配置不再包含真实邮件密码、外部 IP、开发密码或默认生产密钥。
2. `dev` Profile 使用本机 `127.0.0.1` 的 MySQL、Redis、Sandbox 和 Vision AI。
3. `prod` Profile 使用服务器本机回环地址，前端通过 Nginx 的 `/api` 访问 Spring Boot，不直接暴露 8888 端口。
4. 生产环境默认关闭本地 ComfyUI 和 Vision AI；需要演示图像自动标注时，通过服务器私密环境文件显式开启 Vision AI。
5. Spring Boot 启动参数决定环境：`--spring.profiles.active=dev` 或 `--spring.profiles.active=prod`。

## 计划修改范围

- `spingboot-init/src/main/resources/application.yml`
- `spingboot-init/src/main/resources/application-dev.yml`
- `spingboot-init/src/main/resources/application-prod.yml`
- `spingboot-init/src/main/resources/application-local.yml.example`
- 新增服务器环境变量示例和启动说明

## 验收方式

- `dev` Profile 在本机读取本地私密文件且不要求服务器变量。
- `prod` Profile 不含公网数据库、Redis、Sandbox 端口暴露配置。
- 构建通过，且配置文件中没有真实密码或 API Key。

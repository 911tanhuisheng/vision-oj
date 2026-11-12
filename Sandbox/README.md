# Sandbox 判题服务

独立 Spring Boot 服务：**在 Docker 容器内**编译、运行用户提交的代码（Java / Python / C++），与主业务 API 解耦。

## 为什么必须关注部署环境？

用户代码 **不会** 在主服务 JVM 里执行，而是由本服务调用 **Docker Engine** 拉起隔离容器判题。因此：

| 环境 | 建议 |
|------|------|
| **生产 / 公网部署** | **Linux 服务器** + Docker（或 containerd）— 业界 OJ 标准做法 |
| **本地开发** | Windows 可用 **Docker Desktop + WSL2** 联调；macOS 可用 Docker Desktop |
| **仅跑主站、不判题** | 可不启 Sandbox，但提交代码会一直失败 |

> 主服务（`spingboot-init`）可部署在 Windows 或 Linux；**Sandbox 生产环境请部署在 Linux**，与 Docker 同机或同内网，避免跨系统 Docker 套接字问题。

## 依赖

- JDK 17+
- **Docker 已安装并启动**（`docker ps` 可用）
- Redis（限流，与主服务可共用实例）
- 首次运行前建议拉取镜像：

```bash
docker pull eclipse-temurin:17-jdk
docker pull gcc:13
```

图像题需要先构建带 Pillow / OpenCV / Ultralytics / EasyOCR，以及预置通用 YOLOv8n、自训练安全帽模型、EasyOCR 中英文模型的 Python 镜像：

```bash
cd <repo-root>
docker build -t mayue-oj-python-vision:1.0 -f Sandbox/docker/python-vision/Dockerfile .
```

普通 Python 题也可以复用该镜像。图像题请求由主服务从可信 MinIO 读取图片，再传给 Sandbox；
容器仍使用 `network=none`，学生程序从 stdin 读取形如 `/input-images/.image_0.jpg` 的容器内路径。
题图单独以只读目录挂载；学生代码的 `/app` 工作目录仍可写，两个目录相互独立。
通用模型路径为 `/models/yolov8n.pt`，安全帽模型路径为 `/models/hard-hat-best.pt`。

## 配置

`src/main/resources/application.yml`：

| 项 | 说明 |
|----|------|
| `server.port` | 默认 `8080`，context-path `/api` |
| `sandbox.auth.key` | 必须设置至少 32 位随机值，与主服务调用时的 HTTP 头 `auth` 一致；示例密钥不能启动新版本 |
| `sandbox.docker.*-image` | 各语言 Docker 镜像；Python 默认 `mayue-oj-python-vision:1.0` |
| `REDIS_HOST` | Redis 地址 |

## 启动

```bash
cd Sandbox
cp .sandbox.env.example .sandbox.env
# 编辑 .sandbox.env，填写与主服务一致的 SANDBOX_AUTH_KEY
bash start-sandbox.sh
# 源码更新后：bash start-sandbox.sh --restart
```

健康检查：本机访问 `GET http://127.0.0.1:8080/api/health`。判题接口是 `POST /api/sandbox`，需要 `auth` 头。

## 主服务如何连接

主服务 `spingboot-init` 在 `application.yml` 中配置：

```yaml
codesandbox:
  url: http://<Sandbox 主机>:8080/api/sandbox
  auth-header-name: auth
  auth-key: <同一个随机密钥>   # 与 sandbox.auth.key 一致
```

也可用环境变量：`SANDBOX_URL`、`SANDBOX_AUTH_KEY`。**不要** 把 Sandbox 端口直接暴露公网。

## Linux 生产部署要点

1. 安装 Docker：`sudo apt install docker.io` 或官方脚本，并将运行用户加入 `docker` 组。  
2. Sandbox 与 Docker **同一台 Linux 机**（或 Docker 远程 API 需额外安全配置）。  
3. 防火墙：仅内网开放 `8080`，对外只暴露 Nginx + 主 API `8888`。  
4. 限制容器 CPU/内存（已在 `application.yml` 的 `execution`、`security` 中配置）。  

## 故障排查

| 现象 | 可能原因 |
|------|----------|
| 提交一直「系统错误」 | Sandbox 未启动、主服务沙箱 URL 错误、`auth` 不一致 |
| 启动报 Docker 相关错误 | Docker 未运行、当前用户无权限访问 `/var/run/docker.sock` |
| 首次判题很慢 | 正在拉取 `java/python/cpp` 镜像，提前 `docker pull` |

更多架构说明见仓库根目录 [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)。

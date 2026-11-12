# 码跃 OJ（MaYue OJ）

本项目是面向智能科学与技术专业毕业设计开发的在线编程判题系统。对应课题：

> **融合图像识别的智能化在线编程判题系统设计与实现**

传统 OJ 主要比较程序的文本输入与输出，难以直接支持目标检测、OCR 和图像属性分析等视觉编程任务。本系统在常规题库、代码编辑、竞赛和多语言判题基础上，引入 YOLOv8、EasyOCR、OpenCV、MinIO 与独立 Docker Sandbox，形成“题图获取—智能标注—题目生成—隔离运行—多策略判题—结果反馈”的完整闭环。

系统包含管理端和学生端：管理员可以上传题图，调用视觉服务获取待复核的识别结果，并配置公开样例与隐藏用例；题图生成可选本地 ComfyUI 或使用个人百炼 Key 的云端服务。学生编写 Python、Java 或 C++ 程序，代码在受限容器内运行；判题中心根据题型选择文本匹配、数量容差、分类标签、检测框 IoU、OCR 编辑距离或数值容差策略完成评测。视觉模型辅助命题，最终标准答案须由出题人复核；学生程序的输出由明确的规则判定，并非由大模型主观评分。

后端 Spring Boot 3 · 前端 Vue 3 · MySQL · Redis · MinIO · YOLOv8 · EasyOCR · Docker Sandbox

## 研究目标与特色

- 在传统 OJ 中扩展图像目标计数、图像分类、目标检测框、OCR 和图像属性五类视觉编程题。
- 将模型能力用于管理员辅助出题和标准答案生成，同时让学生代码在隔离环境中完成实际推理。
- 使用公开样例和隐藏图像用例分离机制，避免学生直接硬编码样例答案。
- 根据任务语义选择不同判题策略，而不是只进行字符串完全相等比较。
- 支持 COCO 通用目标检测与校园安全帽检测模型切换；模型的当前独立测试指标见下文“实验与验收状态”。
- 题图统一存入 MinIO，出题、做题、判题和提交记录使用同一套数据链路。

## 系统角色

| 角色 | 主要功能 |
|------|----------|
| 管理员（出题者） | AI 辅助出题、题图上传或生成、自动标注、隐藏用例配置、题目与比赛管理 |
| 学生 | 浏览题库、查看公开样例、在线编程、样例运行、提交评测、查看错误原因 |
| 判题节点 | 编译和运行用户代码、限制时间与内存、加载视觉模型、返回标准化执行结果 |

## 图像题闭环

任务书要求、当前实测差距和复测方法见 [任务书验收对照](docs/TASKBOOK_ACCEPTANCE.md)。

```text
上传/拍照/AI 生成题图
          ↓
      MinIO 存储
          ↓
YOLOv8 / EasyOCR / OpenCV 自动标注
          ↓
生成待复核的题面、分类和标准答案
          ↓
公开样例 + 隐藏图像测试用例
          ↓
学生代码进入 Docker Sandbox 运行
          ↓
判题策略比较学生输出与标准结果
          ↓
AC / WA / CE / RE / TLE / MLE
```

其中，自动标注用于减轻教师录题工作量，发布前须由出题人核对图片和标准输出；正式提交使用隐藏图片重新运行学生程序，检查代码是否能处理新输入。

## 功能

- 题库做题、样例运行、提交评测（Monaco 编辑器）
- 竞赛与排行榜
- 用户系统（用户名或注册邮箱登录、JWT、单端登录、签到日历）
- 邮箱绑定与找回密码：账户设置中用当前密码和新邮箱验证码绑定/更换邮箱；未登录时可通过已绑定邮箱重置密码。邮件功能需配置 `MAIL_USERNAME` / `MAIL_PASSWORD`（邮箱 SMTP 授权码）。
- 博客、评论、站内通知
- 管理端（题目 / 比赛 / 公告 / 用户）
- 做题页 AI 助手（可选：站点百炼 Key，或用户自行配置的百炼 / DeepSeek Key）
- 图像编程题：上传/AI 生成题图，YOLOv8、校园安全模型、EasyOCR 自动标注
- 智能判题：文本精确匹配、目标数量容差、分类标签、检测框 IoU、OCR 编辑距离、图像属性容差
- 公开样例与隐藏图像用例隔离，防止学生直接硬编码标准答案

---

## 实验与验收状态

功能实现不等于任务书指标达标。下表只列已有证据；完整要求、复测方法与原始记录见 [任务书验收对照](./docs/TASKBOOK_ACCEPTANCE.md)。

| 项目 | 当前状态 |
| --- | --- |
| 校园安全帽模型 mAP@0.5 | 独立测试集 883 张，三类平均值 **0.6763**，未达到任务书的 0.80；`person` 类仍是主要短板。见 [原始评估记录](./docs/evidence/hard_hat_test.json) |
| OCR 文字准确率 | 尚无独立人工转写测试结果，不能声称达到 90% |
| 判题与人工标注一致率 | 尚无独立人工标注对照结果，不能声称达到 95% |
| 完整提交响应时间与 20 人并发 | 尚无覆盖完整服务链路的实测报告 |

本仓库提供模型训练、评估和并发测试脚本；上述缺口属于毕业设计后续实验工作，不以演示截图或单元测试代替。

---

## 架构

主服务 `spingboot-init` 负责业务、题目和提交记录；`vision-ai` 为出题提供 YOLOv8、EasyOCR、OpenCV 识别；**Sandbox** 通过 Docker 运行用户代码。前端只调用主服务，判题沙箱和视觉服务由主服务在内网调用。

```
浏览器 → Vue 前端 → Spring Boot 主 API (:8888) → MySQL / Redis / MinIO
                         │               │
                         │               └→ vision-ai (:8090) → YOLOv8 / EasyOCR / OpenCV
                         └→ Sandbox (:8080) → Docker → Java / Python / C++ 用户程序
```

更细的部署图、时序图、表结构见 [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)。

图像智能判题闭环见 [docs/VISION_JUDGE_DESIGN.md](./docs/VISION_JUDGE_DESIGN.md)，毕业论文与实验准备见 [docs/GRADUATION_THESIS_GUIDE.md](./docs/GRADUATION_THESIS_GUIDE.md)，Ubuntu 一键部署 Sandbox 见 [deploy/ubuntu/README.md](./deploy/ubuntu/README.md)。

毕业设计提交时不要直接打包虚拟环境和依赖缓存。运行 `powershell -ExecutionPolicy Bypass -File .\tools\create-delivery-package.ps1` 可在项目同级目录生成不含密钥、缓存和编译产物的精简压缩包，清单见 [DELIVERY_CHECKLIST.md](./DELIVERY_CHECKLIST.md)。

> **判题服务建议部署在 Linux + Docker。** 主站和前端可在 Windows 开发；生产环境不要把 Sandbox 端口直接暴露公网。说明见 [Sandbox/README.md](./Sandbox/README.md)。

---

## 目录

```text
前端/myoj/          # Vue 3 前端
spingboot-init/     # 主业务 API（目录名是历史拼写，未改包名）
Sandbox/            # 判题沙箱
vision-ai/          # 图像识别与自动标注 HTTP 服务
deploy/ubuntu/      # Ubuntu 部署脚本与示例配置
docs/               # 架构、实验与部署资料
```

---

## 本地运行

### 依赖

JDK 17+、Maven、Node 20.19+ / 22.12+、Python 3.11、MySQL 8+、Redis、MinIO、Docker（运行 Sandbox 时必需）。本地完整图像题还需要 YOLO / OCR 模型文件和足够内存。

### 步骤

1. 初始化库表：

```bash
mysql -u root -p < spingboot-init/src/main/resources/db/schema.sql
```

此脚本创建 **`tan`** 数据库，包含表结构、开发用管理员 `admin` / `123456`、基础算法题和示例竞赛。已有库导入前先备份；公开部署前必须修改管理员密码。`application-dev.yml` 的默认数据库是 `test`，因此本地若导入 `tan`，须明确设置 `MYSQL_URL` 指向 `tan`。

2. 配置本机连接：参考 [.env.example](./.env.example) 设置操作系统或 IDE 环境变量；也可按 [`application-local.yml.example`](./spingboot-init/src/main/resources/application-local.yml.example) 在 `spingboot-init/` 模块根目录创建被 Git 忽略的 `application-local.yml`。选择 `dev` Profile 且工作目录为项目总目录或 `spingboot-init/` 时，`application-dev.yml` 会自动读取该文件；无需再填写 `--spring.config.additional-location`。Spring Boot 不会自动读取项目根目录的 `.env`；不要提交真实密码和密钥。`dev` 的 Redis 默认端口是 `16379`（供 FinalShell 隧道使用）；本机 Redis 运行在 `6379` 时需覆盖 `REDIS_PORT`。若使用远程 Sandbox，把其 URL 和相同的至少 32 位随机 `SANDBOX_AUTH_KEY` 写入本机私有配置；仓库默认地址是本机 `127.0.0.1:8080`。

3. 先准备 Python 镜像所需模型文件，再构建镜像并启动 Sandbox（需 Docker）：

```bash
docker build -t mayue-oj-python-vision:1.0 -f Sandbox/docker/python-vision/Dockerfile .
cd Sandbox && mvn spring-boot:run
```

构建前需要检查 `Sandbox/docker/python-vision/models/` 下的 `yolov8n.pt`、`hard-hat-best.pt` 和 `easyocr/` 下的两个模型文件。第三方通用权重和 OCR 权重不随 Git 仓库提供；缺少文件时 Docker 的 `COPY` 步骤会失败。Sandbox 还需要 `eclipse-temurin:17-jdk` 与 `gcc:13` 镜像，详见 [Sandbox 使用说明](./Sandbox/README.md)。

4. 在**另一个终端**启动主服务，显式选择 `dev` Profile，并设置与 SQL 脚本一致的数据库地址：

```powershell
cd spingboot-init
# PowerShell 示例；密码等敏感值通过系统或 IDE 环境变量设置
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:3306/tan?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true'
mvn spring-boot:run
```

5. 在**另一个终端**启动 YOLOv8 + EasyOCR 自动标注服务：

```powershell
cd vision-ai
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python run.py
```

若需要“AI 生成题图 → 自动识别 → 自动出题”，Windows + NVIDIA 环境可直接在项目根目录运行：

```powershell
start-image-ai.bat
```

可直接双击 `start-image-ai.bat`。首次运行会自动安装 ComfyUI、NVIDIA PyTorch、YOLO/OCR 依赖并下载 Stable Diffusion 1.5 模型；后续会跳过已安装内容直接启动。新电脑需预先安装 Git、Python 3.12/3.13 和 NVIDIA 显卡驱动。脚本同时启动 `vision-ai:8090` 和 `ComfyUI:8188`，生成图片由后端转存到 MinIO，不依赖阿里云 OSS。

6. 在**另一个终端**启动前端：

```powershell
cd 前端/myoj && npm install && npm run dev
```

管理员可以创建目标计数、图像分类、检测框、OCR、图像属性分析五类图像编程题。选择题型并上传题图后，点击「一键识别并生成题目」，系统会自动生成标题、题面、分类、参考答案、公开样例、标准输出与评测限制；发布前需人工复核 AI 标注。

题目编辑页也可以选择「本地 GPU」或「百炼云端生图」。先选生图模型并生成一张预览图；确认后才会生成两张不同场景的隐藏图，逐张经过 YOLO/OCR 标注，再进行验题和人工复核。生图模型与上方的识别模型是两项独立设置。云端模式只使用当前教师在「账号设置 → AI 模型」保存的北京地域百炼 Key，可能按张计费；未配置 Key 时不会改用站点额度。本地可选模型必须同时出现在 ComfyUI 已安装列表及 `COMFYUI_ALLOWED_CHECKPOINTS`（英文逗号分隔）中。若百炼新模型在旧域名不可用，可由管理员在后端环境设置 `DASHSCOPE_IMAGE_BASE_URL` 为北京地域工作空间专属的 `.../compatible-mode/v1` 地址。已有数据库在主服务启动时自动增加题图来源和生图模型字段。

目标检测类题目可选择 `YOLO_GENERAL`（COCO 通用场景）或 `YOLO_HELMET`（本项目训练的校园安全帽模型，类别为 head / helmet / person）；OCR 与图像属性题分别自动路由到 EasyOCR 和 OpenCV。模型选择会随题目保存，并同步到识别服务、学生参考代码和隔离判题镜像。

### 个人大模型配置

已有 `tan` 数据库在主服务启动时会自动创建个人模型所需的两张缺失表，不清空现有数据；如果数据库账号没有建表权限，先备份数据库，再检查权限或执行 `spingboot-init/src/main/resources/db/schema.sql`。在主服务环境设置 `AI_CREDENTIAL_ENCRYPTION_KEY`：它必须是 **32 字节随机值的 Base64**，可用 `openssl rand -base64 32` 生成。妥善备份此值；更换或丢失后，已保存的个人 API Key 无法解密，需要用户重新填写。不要把密钥写入 Git 或截图。

启动后，用户进入「账号设置 → AI 模型」保存自己的百炼或 DeepSeek API Key、模型名并测试连接；做题助手和管理员传统题目 AI 录入页都可切换模型。未配置个人平台时，只有站点同时设置 `BAILIAN_ENABLED=true` 和有效 `DASHSCOPE_API_KEY`，才能使用站点默认百炼模型。用户保存的 API Key 仅在服务端解密、调用对应平台；浏览器只保存非敏感的模型选择。`ai_usage_log` 只记录平台、模型、功能、耗时和是否成功，不记录密钥、题目代码或对话内容。YOLO、OCR、ComfyUI 与程序判题不使用这里的大模型配置。

- 页面：http://localhost:5173  
- 接口文档：http://localhost:8888/api/doc.html  

### 生产部署概览

- 前端运行 `npm run build` 后由 Nginx 提供静态页面；`/api/` 转发到仅供本机访问的主服务，参考 [Nginx 示例](./前端/myoj/deploy/nginx.example.conf)。
- 主服务使用 `prod` Profile；JAR 内的 `application-prod.yml` 只有环境变量占位，不含真实密码。MySQL、Redis、MinIO、Sandbox 和 `vision-ai` 的地址及密钥由服务器环境变量提供。不要把真实配置打进 JAR 或提交 Git。
- Sandbox 在 Linux + Docker 上运行，`vision-ai` 可复用已构建的 Python 视觉镜像；二者的端口不对公网开放。见 [Ubuntu 部署说明](./deploy/ubuntu/README.md)与 [视觉服务部署说明](./vision-ai/README.md)。
- 本地 ComfyUI、百炼生图和做题助手都是可选功能，按实际部署情况启用；未部署时不要在演示中声称可用。

---

## 文档

| 文件 | 说明 |
|------|------|
| [docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md) | 架构与接口模块 |
| [docs/VISION_JUDGE_DESIGN.md](./docs/VISION_JUDGE_DESIGN.md) | 图像出题、模型路由与智能判题闭环 |
| [docs/GRADUATION_THESIS_GUIDE.md](./docs/GRADUATION_THESIS_GUIDE.md) | 毕业论文结构、实验指标与答辩演示建议 |
| [docs/TASKBOOK_ACCEPTANCE.md](./docs/TASKBOOK_ACCEPTANCE.md) | 任务书指标、真实测试结果与待完成验收 |
| [spingboot-init/src/main/resources/db/README.md](./spingboot-init/src/main/resources/db/README.md) | 数据库脚本 |
| [Sandbox/README.md](./Sandbox/README.md) | 判题机部署 |
| [vision-ai/README.md](./vision-ai/README.md) | YOLO / OCR 服务的运行和部署 |
| [deploy/ubuntu/README.md](./deploy/ubuntu/README.md) | Ubuntu 判题节点部署与验收 |
| [DELIVERY_CHECKLIST.md](./DELIVERY_CHECKLIST.md) | 源码提交、恢复和验收清单 |

---

## 安全与开源

**公开前检查**

- `application-dev.yml` 和 `application-prod.yml` 只保存环境变量占位或非敏感默认值。真实密码、令牌和个人模型加密密钥必须放在本地私有文件、宝塔项目环境变量或服务器 `/etc/mayue-oj/app.env` 中；更新 JAR 不会覆盖服务器环境变量。
- `.env`、模块根目录的 `application-local.yml`、`*.private-backup.yml`、虚拟环境、运行日志和构建产物应留在本机；首次克隆后参考 [.env.example](./.env.example) 和 `application-local.yml.example` 自行配置。提交前仍需扫描 Git 状态和历史。
- 本地的 `vision-ai.tar`、第三方模型权重和 OCR 权重可能很大，已在 `.gitignore` 排除；提交前仍应检查 `git status` 与文件大小。当前 Dockerfile 需要部分不随仓库提供的模型文件，按上文准备后才能构建。

**若曾提交过含密钥的旧配置**

- 在 MinIO、百炼、邮箱、JWT 等服务中**轮换已暴露的密钥**后再公开仓库；仅修改当前文件无法清除 Git 历史中的泄露。

**许可**

- 本仓库源码按 [MIT](./LICENSE) 许可提供；第三方模型、数据集及其权重的许可另行遵守原权利人的条款。
- 源码包不分发任何第三方音乐或歌词。音乐播放器组件仅作为前端功能实现保留；如需演示，应使用本人原创或具有明确再分发许可的音频，并另行标注作者与许可证。

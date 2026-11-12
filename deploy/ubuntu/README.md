# Ubuntu 主服务与判题节点部署

## 主业务 API（宝塔服务器）

主服务使用 `prod` Profile。JAR 只携带 `${环境变量名}` 占位，真实密码和密钥由服务器提供：使用下面的 systemd 服务时放在 `/etc/mayue-oj/app.env`；使用宝塔 Java 项目时在该项目的环境变量设置中填写。不要上传到 Git、打进 JAR 或发到聊天记录。

至少提供 `MYSQL_PASSWORD`、`JWT_SECRET`、`MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY` 和 `SANDBOX_AUTH_KEY`。邮箱功能需要额外设置 `MAIL_USERNAME`、`MAIL_PASSWORD`；站点百炼助手需要 `BAILIAN_ENABLED=true` 和 `DASHSCOPE_API_KEY`；允许用户保存个人模型 Key 时需要稳定的 `AI_CREDENTIAL_ENCRYPTION_KEY`。首次配置可参考 [无密钥模板](./mayue-oj.env.example)。更换个人模型加密密钥后，已保存的个人 Key 无法解密。已有用户的站点还必须保持原有 `APP_SECURITY_PEPPER`，否则旧密码无法验证。

### 已使用宝塔 Java 项目的情况

在宝塔「网站 → Java 项目 → 对应项目 → 设置」的环境变量中，选择指定变量，**每行填写一个 `KEY=VALUE`**，例如 `SPRING_PROFILES_ACTIVE=prod` 和上述实际变量。不要把一整串 Base64 值单独放入输入框；环境变量必须带变量名。启动命令保留 `--spring.profiles.active=prod` 也可以。保存后重启 Java 项目，换新 JAR 时环境变量仍由宝塔项目配置提供；仅在服务器创建 `/etc/mayue-oj/app.env`，宝塔 Java 项目不会自动读取它。

如果改用下面的 systemd 服务，先停止宝塔里同端口的旧 Java 项目，避免两个后端同时占用 8888。

```bash
# 在项目根目录执行：先构建 JAR
cd spingboot-init
mvn -q -DskipTests clean package

# 上传 target/spingboot-init-0.0.1-SNAPSHOT.jar、mayue-oj.env.example 和
# mayue-oj.service 到 /www/wwwroot/mayue-oj/server/；JAR 上传时改名为 mayue-oj.jar。
# 然后在服务器执行：
sudo mkdir -p /etc/mayue-oj
sudo cp /www/wwwroot/mayue-oj/server/mayue-oj.env.example /etc/mayue-oj/app.env
sudo nano /etc/mayue-oj/app.env
sudo chmod 600 /etc/mayue-oj/app.env

# 将仓库中的 deploy/ubuntu/mayue-oj.service 上传到服务器后执行：
sudo cp /www/wwwroot/mayue-oj/server/mayue-oj.service /etc/systemd/system/mayue-oj.service
sudo systemctl daemon-reload
sudo systemctl enable --now mayue-oj
sudo systemctl status mayue-oj
```

服务器目录约定：

```text
/www/wwwroot/mayue-oj/             # Vue 前端 dist
/www/wwwroot/mayue-oj/server/      # mayue-oj.jar 与部署模板副本
/etc/mayue-oj/app.env              # 仅服务器保存的真实密钥
```

确认 API 已在本机监听：

```bash
curl http://127.0.0.1:8888/api/doc.html
```

宝塔 Nginx 只反代 `/api/` 到 `http://127.0.0.1:8888/api/`；不要开放 8888、3306、6379、8080、8090 到公网。4GB 服务器默认关闭 Vision AI 与 ComfyUI。**替换新版 JAR 前**，若现有站点在使用视觉自动标注、本地/云端生图或站点百炼助手，分别确认服务器已有 `VISION_AI_ENABLED=true`、`IMAGE_GENERATION_ENABLED=true`、`BAILIAN_ENABLED=true` 及对应服务和 Key；这些功能现在不会因生产配置的默认值自动开启。使用 systemd 时编辑 `/etc/mayue-oj/app.env`，使用宝塔 Java 项目时在该项目的环境变量中设置，然后重启主服务。

## 首次部署

```bash
cd <项目根目录>
cp deploy/ubuntu/sandbox.env.example deploy/ubuntu/sandbox.env
nano deploy/ubuntu/sandbox.env
# 将 SANDBOX_AUTH_KEY 设置为至少 32 位随机值，例如先用 openssl rand -hex 32 生成
chmod +x deploy/ubuntu/start-sandbox.sh
./deploy/ubuntu/start-sandbox.sh
```

脚本会检查 Docker、Java、Maven和安全帽权重，构建 `mayue-oj-python-vision:1.0`，打包并后台启动 Sandbox，最后确认 Docker 健康状态。

主服务配置：

```text
SANDBOX_URL=http://<Ubuntu-IP>:8080/api/sandbox
SANDBOX_AUTH_KEY=<与 sandbox.env 完全一致>
```

更新源码后，先同步代码和同一密钥，再运行 `./deploy/ubuntu/start-sandbox.sh --restart`。
若最初使用 `Sandbox/start-sandbox.sh` 启动，则继续使用 `bash Sandbox/start-sandbox.sh --restart`。
两种脚本使用不同的 PID 文件；不要交叉重启。普通 Python 与图像题仍用同一个镜像，修改沙箱 Java 代码无需重建大镜像。

不要把 8080 端口直接暴露到公网，只允许主 API 服务器访问。

## Windows 主服务 + Ubuntu Sandbox 更新顺序

仓库内 `Sandbox/.sandbox.env` 和 `spingboot-init/src/main/resources/application-local.yml` 是被 Git 忽略的本机配置，**不要上传到仓库**。更换沙箱共享密钥时按以下顺序操作，避免两端短暂失联：

1. 将已验证的 Sandbox 源码同步到 Ubuntu 的 `~/Sandbox`。同时把 Windows 的 `Sandbox/.sandbox.env` 私下复制到 Ubuntu 的 `~/Sandbox/.sandbox.env`；不要放进公开压缩包。
2. 若旧服务由 `Sandbox/start-sandbox.sh` 启动，在 `~/Sandbox` 运行 `bash start-sandbox.sh --restart`；若由本目录脚本启动，在项目根目录运行 `bash deploy/ubuntu/start-sandbox.sh --restart`。若提示端口由脚本之外的进程占用，先在原终端停止旧服务，再正常启动。
3. 在 Ubuntu 运行 `python3 ~/Sandbox/smoke_test.py`，确认普通题、注释和题图只读三项均通过。
4. 回到 Windows 项目根目录，运行 `powershell -ExecutionPolicy Bypass -File .\tools\activate-sandbox-key.ps1`，然后重启主后端。该脚本从本地忽略文件读取密钥，不在终端打印密钥。

旧实例未更新前不要先启用主后端的新密钥。新版本 Sandbox 会拒绝示例或过短的共享密钥。

## 验收

```bash
curl http://127.0.0.1:8080/api/health
docker image inspect mayue-oj-python-vision:1.0
docker run --rm --network none mayue-oj-python-vision:1.0 \
  python3 -c "from ultralytics import YOLO; print(YOLO('/models/hard-hat-best.pt').names)"
```

健康接口必须返回 `status=ok`，模型类别必须包含 `head`、`helmet`、`person`。

如启动失败：

```bash
tail -n 200 .runtime-logs/sandbox.log
docker info
```

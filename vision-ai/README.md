# Vision AI service

YOLOv8 + EasyOCR automatic annotation service for OJ image questions.

Supported authoring modes:

- object counting
- dominant-object classification
- object detection boxes
- Chinese/English OCR
- basic image feature analysis

```powershell
cd vision-ai
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python run.py
```

Health check: `GET http://127.0.0.1:8090/health`.

## Ubuntu server (reuse the existing sandbox vision image)

The server must already have the local `mayue-oj-python-vision:1.0` image.
Upload `app/`, `run.py`, `Dockerfile.server`, and `.dockerignore` to a
`/www/wwwroot/vision-ai/` on the server, separate from the sandbox source.
The large model files are already in
the sandbox image; do not upload `.venv/`, training outputs, or duplicate
weights for this deployment.

```bash
free -h
df -h /
docker image inspect mayue-oj-python-vision:1.0 >/dev/null
cd /www/wwwroot/vision-ai
docker build -f Dockerfile.server -t mayue-oj-vision-ai:1.0 .
docker run -d --name mayue-vision --restart unless-stopped \
  --network host --memory 1600m --memory-swap 2g \
  mayue-oj-vision-ai:1.0
docker logs --tail 60 mayue-vision
curl -f http://127.0.0.1:8090/health
```

The service listens only on the host loopback address. Do not add an Nginx
route or open port 8090 to the public internet. `/health` checks that the API
process is running; test `/detect` with a real image URL before enabling the
Java backend. In the Baota Java project's environment variables, set
`VISION_AI_ENABLED=true` and `VISION_AI_BASE_URL=http://127.0.0.1:8090`, then
restart the Java project. The production profile otherwise leaves this
service disabled.

On a 4 GB server, check `free -h`, `df -h`, and `docker stats --no-stream`
before enabling the service. YOLO and OCR share the server with Java, MySQL,
MinIO, and the sandbox. If the process is killed for lack of memory, run this
service on another machine and point `VISION_AI_BASE_URL` to a private address.

Windows 上也可以直接双击项目根目录的 `start-vision-ai.bat`，只启动 YOLO/OCR。
首次运行会自动创建虚拟环境并安装本服务依赖；运行时请保持窗口打开。
如果还需要 ComfyUI 生图服务，请使用根目录的 `start-image-ai.bat`。

The first general detection downloads `yolov8n.pt`; the first OCR request downloads
the EasyOCR Chinese/English models. Model routing keys are:

- `YOLO_GENERAL`: COCO general detection (`VISION_MODEL_GENERAL`)
- `YOLO_HELMET`: trained campus hard-hat detection (`VISION_MODEL_HELMET`)
- `EASYOCR_ZH_EN`: Chinese/English OCR
- `OPENCV_ANALYSIS`: image size, grayscale mean and edge strength

`POST /detect` accepts `modelKey`. The default custom model is
`models/hard-hat-best.pt`.

FROM python:3.11-slim

# Model weights are bundled with this project, so the image never fetches
# YOLO or EasyOCR models from GitHub during build or runtime.
ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    YOLO_CONFIG_DIR=/tmp/Ultralytics \
    EASYOCR_MODULE_PATH=/opt/easyocr

# System and Python dependencies use domestic mirrors and separate cacheable
# layers. A successfully built layer is reused on the next Docker build.
RUN sed -i 's|deb.debian.org|mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update \
    && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --retries 5 --timeout 120 -i https://pypi.tuna.tsinghua.edu.cn/simple \
    pillow opencv-python-headless ultralytics easyocr

COPY Sandbox/docker/python-vision/models/yolov8n.pt /models/yolov8n.pt
COPY Sandbox/docker/python-vision/models/hard-hat-best.pt /models/hard-hat-best.pt
COPY Sandbox/docker/python-vision/models/easyocr/craft_mlt_25k.pth /opt/easyocr/model/craft_mlt_25k.pth
COPY Sandbox/docker/python-vision/models/easyocr/zh_sim_g2.pth /opt/easyocr/model/zh_sim_g2.pth

WORKDIR /app

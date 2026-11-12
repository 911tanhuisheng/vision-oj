"""Run after starting the Ubuntu Sandbox. Reads its local ignored secret file."""

import base64
import json
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener


ROOT = Path(__file__).resolve().parent
ENV_FILE = ROOT / ".sandbox.env"


def read_setting(name: str) -> str:
    if not ENV_FILE.is_file():
        raise SystemExit("缺少 Sandbox/.sandbox.env，请先配置并启动沙箱")
    for raw in ENV_FILE.read_text(encoding="utf-8").splitlines():
        key, sep, value = raw.partition("=")
        if sep and key.strip() == name:
            return value.strip().strip('"').strip("'")
    raise SystemExit(f"缺少配置项 {name}")


KEY = read_setting("SANDBOX_AUTH_KEY")
PORT = read_setting("SERVER_PORT")
URL = f"http://127.0.0.1:{PORT}/api/sandbox"


def execute(code: str, images=None) -> dict:
    body = {
        "language": "python",
        "code": code,
        "inputList": ["fixture" if images else "1 2"],
    }
    if images is not None:
        body["imageBase64List"] = images
    request = Request(
        URL,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "auth": KEY},
        method="POST",
    )
    with build_opener(ProxyHandler({})).open(request, timeout=90) as response:
        return json.load(response)


def expect(name: str, response: dict, output: str) -> None:
    if response.get("code") != 0 or response.get("outputList") != [output]:
        raise SystemExit(f"{name}失败：{response.get('resultType')} / {response.get('message')}")
    print(f"{name}：通过")


expect("普通 Python", execute("a,b=map(int,input().split());print(a+b)"), "3")
expect("注释不误拦", execute("print(3)\n# import os is only a comment"), "3")

image = base64.b64encode(b"\x89PNG\r\n\x1a\n").decode("ascii")
readonly_code = """from pathlib import Path
p = Path(input().strip())
try:
    p.chmod(0o600)
    p.write_bytes(b'changed')
    print('writable')
except OSError:
    print('readonly')
"""
expect("题图只读挂载", execute(readonly_code, [image]), "readonly")

from __future__ import annotations

import argparse
import base64
import binascii
import io
import json
import sys
import time
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Timer
from typing import Any

import numpy as np
import torch
from PIL import Image, ImageOps, UnidentifiedImageError
from sklearn.cluster import KMeans


SAMPLE_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SAMPLE_DIR.parent
INDEX_PATH = SAMPLE_DIR / "index.html"
MODEL_PATH = PROJECT_DIR / "model.pth"
IMAGE_SIZE = (128, 128)
NUM_COLORS = 4
MAX_REQUEST_BYTES = 20 * 1024 * 1024

if str(PROJECT_DIR) not in sys.path:
    sys.path.insert(0, str(PROJECT_DIR))

from model import PattleCNN  # noqa: E402


def _sort_by_brightness(colors: np.ndarray) -> np.ndarray:
    brightness = (
        colors[:, 0] * 0.2126
        + colors[:, 1] * 0.7152
        + colors[:, 2] * 0.0722
    )
    return colors[np.argsort(brightness)]


def _colors_to_json(colors: np.ndarray) -> list[dict[str, Any]]:
    rgb_colors = np.clip(np.rint(colors * 255), 0, 255).astype(np.uint8)
    return [
        {
            "hex": "#{:02X}{:02X}{:02X}".format(*rgb),
            "rgb": [int(channel) for channel in rgb],
        }
        for rgb in rgb_colors
    ]


def _image_to_data_url(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/png;base64,{encoded}"


def _quantize_with_palette(image_array: np.ndarray, colors: np.ndarray) -> Image.Image:
    pixels = image_array.reshape(-1, 3).astype(np.float32) / 255.0
    distances = np.sum((pixels[:, None, :] - colors[None, :, :]) ** 2, axis=2)
    nearest = np.argmin(distances, axis=1)
    palette = np.clip(np.rint(colors * 255), 0, 255).astype(np.uint8)
    quantized = palette[nearest].reshape(image_array.shape)
    return Image.fromarray(quantized, mode="RGB")


class PaletteAnalyzer:
    def __init__(self, model_path: Path = MODEL_PATH) -> None:
        if not model_path.is_file():
            raise FileNotFoundError(f"找不到模型权重：{model_path}")

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.model = PattleCNN().to(self.device)
        state_dict = torch.load(model_path, map_location=self.device, weights_only=True)
        self.model.load_state_dict(state_dict)
        self.model.eval()

    def analyze(self, raw_image: bytes) -> dict[str, Any]:
        try:
            with Image.open(io.BytesIO(raw_image)) as source:
                source.load()
                image = ImageOps.exif_transpose(source).convert("RGB")
        except (UnidentifiedImageError, OSError, ValueError) as exc:
            raise ValueError("文件不是可识别的图片，或图片已经损坏。") from exc

        original_width, original_height = image.size
        resized = image.resize(IMAGE_SIZE, Image.Resampling.LANCZOS)
        image_array = np.asarray(resized, dtype=np.uint8).copy()
        normalized_pixels = image_array.reshape(-1, 3).astype(np.float32) / 255.0

        kmeans_started = time.perf_counter()
        kmeans = KMeans(
            n_clusters=NUM_COLORS,
            n_init=5,
            random_state=42,
        ).fit(normalized_pixels)
        kmeans_colors = _sort_by_brightness(kmeans.cluster_centers_)
        kmeans_ms = (time.perf_counter() - kmeans_started) * 1000

        cnn_input = (
            torch.from_numpy(image_array)
            .permute(2, 0, 1)
            .unsqueeze(0)
            .to(self.device, dtype=torch.float32)
            / 255.0
        )
        cnn_started = time.perf_counter()
        with torch.inference_mode():
            cnn_colors = self.model(cnn_input)[0].clamp(0, 1).cpu().numpy()
        if self.device.type == "cuda":
            torch.cuda.synchronize(self.device)
        cnn_ms = (time.perf_counter() - cnn_started) * 1000
        cnn_colors = _sort_by_brightness(cnn_colors)

        return {
            "width": original_width,
            "height": original_height,
            "device": str(self.device),
            "kmeans": {
                "colors": _colors_to_json(kmeans_colors),
                "preview": _image_to_data_url(
                    _quantize_with_palette(image_array, kmeans_colors)
                ),
                "elapsed_ms": round(kmeans_ms, 1),
            },
            "cnn": {
                "colors": _colors_to_json(cnn_colors),
                "preview": _image_to_data_url(
                    _quantize_with_palette(image_array, cnn_colors)
                ),
                "elapsed_ms": round(cnn_ms, 1),
            },
        }


def _decode_image_data_url(value: Any) -> bytes:
    if not isinstance(value, str) or "," not in value:
        raise ValueError("没有收到有效的图片数据。")

    metadata, encoded = value.split(",", 1)
    if not metadata.startswith("data:image/") or ";base64" not in metadata:
        raise ValueError("仅支持以 Base64 上传的图片文件。")

    try:
        raw_image = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("图片数据无法解码。") from exc

    if not raw_image:
        raise ValueError("图片内容为空。")
    if len(raw_image) > MAX_REQUEST_BYTES:
        raise ValueError("图片不能超过 20 MB。")
    return raw_image


def create_handler(analyzer: PaletteAnalyzer) -> type[BaseHTTPRequestHandler]:
    class AppHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            if self.path not in ("/", "/index.html"):
                self._send_json({"error": "页面不存在。"}, HTTPStatus.NOT_FOUND)
                return

            try:
                content = INDEX_PATH.read_bytes()
            except OSError:
                self._send_json(
                    {"error": "无法读取界面文件 index.html。"},
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                )
                return

            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)

        def do_POST(self) -> None:
            if self.path != "/api/analyze":
                self._send_json({"error": "接口不存在。"}, HTTPStatus.NOT_FOUND)
                return

            try:
                content_length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                content_length = 0
            if content_length <= 0 or content_length > MAX_REQUEST_BYTES * 2:
                self._send_json(
                    {"error": "请求为空或图片过大。"},
                    HTTPStatus.REQUEST_ENTITY_TOO_LARGE,
                )
                return

            try:
                payload = json.loads(self.rfile.read(content_length))
                raw_image = _decode_image_data_url(payload.get("image"))
                result = analyzer.analyze(raw_image)
            except (json.JSONDecodeError, ValueError) as exc:
                self._send_json({"error": str(exc)}, HTTPStatus.BAD_REQUEST)
                return
            except Exception as exc:
                print(f"分析图片时发生错误：{exc}", file=sys.stderr)
                self._send_json(
                    {"error": "分析图片时发生内部错误，请查看终端输出。"},
                    HTTPStatus.INTERNAL_SERVER_ERROR,
                )
                return

            self._send_json(result, HTTPStatus.OK)

        def _send_json(self, payload: dict[str, Any], status: HTTPStatus) -> None:
            content = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, format: str, *args: Any) -> None:
            return

    return AppHandler


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="KMeans / CNN 图片取色对比工具")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址")
    parser.add_argument("--port", default=7860, type=int, help="监听端口")
    parser.add_argument(
        "--no-browser",
        action="store_true",
        help="启动后不自动打开浏览器",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    analyzer = PaletteAnalyzer()
    server = ThreadingHTTPServer((args.host, args.port), create_handler(analyzer))
    actual_port = server.server_address[1]
    browser_host = "127.0.0.1" if args.host in ("0.0.0.0", "::") else args.host
    url = f"http://{browser_host}:{actual_port}"

    print(f"取色对比工具已启动：{url}")
    print(f"推理设备：{analyzer.device}；按 Ctrl+C 停止服务。")
    if not args.no_browser:
        Timer(0.6, lambda: webbrowser.open(url)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Renders the launcher PNGs for API < 26 with the same artwork as the adaptive icon.

API 26+ uses mipmap-anydpi-v26/ic_launcher.xml (pure vector). Older devices need
raster fallbacks, so this script rasterises the very same leaf mark — a
self-contained SVG path renderer (arcs -> cubic beziers -> polylines) on top of a
diagonal gradient — into every density bucket.

    python3 tools/make_launcher_icons.py
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# The Leaf Lite mark, straight from the mockup (24x24 viewport).
LEAF_PATHS = [
    "M11,20 A7,7 0 0,1 9.8,6.1 C15.5,5 17,4.48 19,2 "
    "c1,2 2,4.18 2,8 c0,5.5 -4.78,10 -10,10 Z",
    "M2,21 c0,-3 1.85,-5.36 5.08,-6 C9.5,14.52 12,13 13,12",
]

GRADIENT_START = (0x2C, 0xC0, 0x7C)
GRADIENT_END = (0x0E, 0x6B, 0x41)

CANVAS = 108.0          # adaptive-icon canvas
ART_OFFSET = 27.0       # 66dp safe zone starts at 27
ART_SCALE = 2.25        # 24dp artwork -> 54dp
STROKE = 1.9 * ART_SCALE
SUPERSAMPLE = 4


# --------------------------------------------------------------- path renderer

def _numbers(text: str) -> list[float]:
    tokens = text.replace(",", " ").split()
    values = []
    for token in tokens:
        values.append(float(token))
    return values


def _arc_to_beziers(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2):
    """SVG endpoint-arc parameterisation -> cubic bezier segments."""
    if rx == 0 or ry == 0:
        return []
    phi = math.radians(phi_deg)
    cos_phi, sin_phi = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x1 - x2) / 2.0, (y1 - y2) / 2.0
    x1p = cos_phi * dx2 + sin_phi * dy2
    y1p = -sin_phi * dx2 + cos_phi * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        scale = math.sqrt(lam)
        rx *= scale
        ry *= scale
    sign = -1 if (large == sweep) else 1
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coeff = sign * math.sqrt(max(num / den, 0.0)) if den else 0.0
    cxp = coeff * (rx * y1p / ry)
    cyp = coeff * -(ry * x1p / rx)
    cx = cos_phi * cxp - sin_phi * cyp + (x1 + x2) / 2.0
    cy = sin_phi * cxp + cos_phi * cyp + (y1 + y2) / 2.0

    def angle(ux, uy, vx, vy):
        length = math.hypot(ux, uy) * math.hypot(vx, vy)
        value = max(-1.0, min(1.0, (ux * vx + uy * vy) / length if length else 0.0))
        theta = math.acos(value)
        return -theta if (ux * vy - uy * vx) < 0 else theta

    theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    delta = angle(
        (x1p - cxp) / rx, (y1p - cyp) / ry,
        (-x1p - cxp) / rx, (-y1p - cyp) / ry,
    )
    if sweep == 1 and delta < 0:
        delta += 2 * math.pi
    elif sweep == 0 and delta > 0:
        delta -= 2 * math.pi

    def to_canvas(px, py):
        return (
            cos_phi * px - sin_phi * py + cx,
            sin_phi * px + cos_phi * py + cy,
        )

    segments = []
    steps = max(int(math.ceil(abs(delta) / (math.pi / 2.0))), 1)
    step_delta = delta / steps
    alpha = 4.0 / 3.0 * math.tan(step_delta / 4.0)
    for index in range(steps):
        theta = theta1 + index * step_delta
        end_theta = theta + step_delta
        ex, ey = rx * math.cos(theta), ry * math.sin(theta)
        edx, edy = -rx * math.sin(theta), ry * math.cos(theta)
        fx, fy = rx * math.cos(end_theta), ry * math.sin(end_theta)
        fdx, fdy = -rx * math.sin(end_theta), ry * math.cos(end_theta)
        p1 = to_canvas(ex, ey)
        p2 = to_canvas(fx, fy)
        c1 = to_canvas(ex + alpha * edx, ey + alpha * edy)
        c2 = to_canvas(fx - alpha * fdx, fy - alpha * fdy)
        segments.append((p1, c1, c2, p2))
    return segments


def _flatten_cubic(p0, c1, c2, p3, steps=24):
    points = []
    for i in range(1, steps + 1):
        t = i / steps
        mt = 1 - t
        x = (mt ** 3) * p0[0] + 3 * (mt ** 2) * t * c1[0] + 3 * mt * (t ** 2) * c2[0] + (t ** 3) * p3[0]
        y = (mt ** 3) * p0[1] + 3 * (mt ** 2) * t * c1[1] + 3 * mt * (t ** 2) * c2[1] + (t ** 3) * p3[1]
        points.append((x, y))
    return points


def path_to_polylines(data: str) -> list[list[tuple[float, float]]]:
    """Supports M/m L/l C/c A/a Z/z — everything the leaf artwork uses."""
    tokens = _tokenize(data)
    polylines: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    index = 0
    cx = cy = 0.0
    start = (0.0, 0.0)

    def ensure():
        if not current:
            current.append((cx, cy))

    while index < len(tokens):
        command = tokens[index][0]
        values = [float(v) for v in tokens[index][1:]]
        index += 1
        if command in ("M", "m"):
            if current:
                polylines.append(current)
            dx, dy = values[0], values[1]
            cx, cy = (dx, dy) if command == "M" else (cx + dx, cy + dy)
            start = (cx, cy)
            current = [(cx, cy)]
        elif command in ("L", "l"):
            for i in range(0, len(values), 2):
                dx, dy = values[i], values[i + 1]
                cx, cy = (dx, dy) if command == "L" else (cx + dx, cy + dy)
                current.append((cx, cy))
        elif command in ("C", "c"):
            for i in range(0, len(values), 6):
                pts = values[i:i + 6]
                if command == "c":
                    c1 = (cx + pts[0], cy + pts[1])
                    c2 = (cx + pts[2], cy + pts[3])
                    end = (cx + pts[4], cy + pts[5])
                else:
                    c1 = (pts[0], pts[1])
                    c2 = (pts[2], pts[3])
                    end = (pts[4], pts[5])
                current.extend(_flatten_cubic((cx, cy), c1, c2, end))
                cx, cy = end
        elif command in ("A", "a"):
            for i in range(0, len(values), 7):
                pts = values[i:i + 7]
                rx, ry, rotation, large, sweep = pts[0], pts[1], pts[2], int(pts[3]), int(pts[4])
                x2, y2 = pts[5], pts[6]
                if command == "a":
                    x2, y2 = cx + x2, cy + y2
                for p1, c1, c2, p2 in _arc_to_beziers(
                    cx, cy, rx, ry, rotation, large, sweep, x2, y2
                ):
                    current.extend(_flatten_cubic(p1, c1, c2, p2, steps=16))
                cx, cy = x2, y2
        elif command in ("Z", "z"):
            if current:
                current.append(start)
                polylines.append(current)
                current = []
                cx, cy = start
        elif command in ("S", "s", "Q", "q", "T", "t", "H", "h", "V", "v"):
            raise ValueError(f"unsupported path command {command}")
        else:
            raise ValueError(f"unknown path command {command}")
    if current:
        polylines.append(current)
    return polylines


def _tokenize(data: str) -> list[list[str]]:
    import re

    pattern = re.compile(r"([MmLlCcAaZzSsQqTtHhVv])|(-?\d*\.?\d+(?:e[-+]?\d+)?)")
    tokens: list[list[str]] = []
    for match in pattern.finditer(data):
        letter, number = match.group(1), match.group(2)
        if letter:
            tokens.append([letter])
        elif number:
            if not tokens or not tokens[-1][0].isalpha():
                raise ValueError("path data must start with a command")
            tokens[-1].append(number)
    return tokens


# -------------------------------------------------------------------- painting

def draw_base(size: int) -> Image.Image:
    gradient = Image.new("RGB", (size, size))
    pixels = gradient.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2.0 * (size - 1)) if size > 1 else 0.0
            pixels[x, y] = (
                int(GRADIENT_START[0] + (GRADIENT_END[0] - GRADIENT_START[0]) * t),
                int(GRADIENT_START[1] + (GRADIENT_END[1] - GRADIENT_START[1]) * t),
                int(GRADIENT_START[2] + (GRADIENT_END[2] - GRADIENT_START[2]) * t),
            )
    return gradient.convert("RGBA")


def draw_leaf(size: int) -> Image.Image:
    layer = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(layer)
    scale = size / CANVAS
    width = max(1, int(round(STROKE * scale)))
    for data in LEAF_PATHS:
        for polyline in path_to_polylines(data):
            points = [
                (
                    (ART_OFFSET + x * ART_SCALE) * scale,
                    (ART_OFFSET + y * ART_SCALE) * scale,
                )
                for x, y in polyline
            ]
            if len(points) > 1:
                draw.line(points, fill=(255, 255, 255, 255), width=width, joint="curve")
    return layer


def build(scale: int = SUPERSAMPLE) -> Image.Image:
    size = int(CANVAS * scale)
    icon = Image.new("RGBA", (size, size))
    gradient = draw_base(64).resize((size, size), Image.BILINEAR)
    icon.paste(gradient, (0, 0))
    icon.alpha_composite(draw_leaf(size))
    return icon


def main() -> None:
    master = build()
    for folder, size in SIZES.items():
        target = RES / folder
        target.mkdir(parents=True, exist_ok=True)
        icon = master.resize((size, size), Image.LANCZOS)
        icon.save(target / "ic_launcher.png", optimize=True)

        rounded = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        mask = mask.resize((size, size), Image.LANCZOS)
        rounded.paste(icon, (0, 0), mask)
        rounded.save(target / "ic_launcher_round.png", optimize=True)
        print(f"  {folder:<16} {size}x{size}")


if __name__ == "__main__":
    main()

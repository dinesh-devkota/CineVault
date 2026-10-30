#!/usr/bin/env python3
"""Generates the tvOS brand assets (layered app icons + top shelf images).

Pure standard library so it runs anywhere (CI, Windows, macOS):
    python3 scripts/generate_app_icons.py
"""
import json
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "CineVault", "Assets.xcassets")
INFO = {"author": "xcode", "version": 1}
TOP = (20, 24, 48)
BOTTOM = (90, 20, 60)
ACCENT = (245, 180, 40)


def write_png(path, w, h, row_fn, alpha):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += row_fn(y)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    color_type = 6 if alpha else 2
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, color_type, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def background_row(w, h):
    def row(y):
        t = y / max(h - 1, 1)
        return bytes(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)) * w
    return row


def play_spans(w, h, y):
    """Horizontal pixel spans of a ring with a play triangle, centred in w x h."""
    cx, cy = w / 2, h / 2
    r_out = h * 0.36
    r_in = r_out * 0.86
    spans = []
    dy = y + 0.5 - cy
    if abs(dy) < r_out:
        xo = (r_out ** 2 - dy ** 2) ** 0.5
        if abs(dy) < r_in:
            xi = (r_in ** 2 - dy ** 2) ** 0.5
            spans += [(cx - xo, cx - xi), (cx + xi, cx + xo)]
        else:
            spans.append((cx - xo, cx + xo))
    th = r_in * 0.55
    if abs(dy) < th:
        left = cx - th * 0.6
        spans.append((left, left + (th - abs(dy)) * 1.7))
    return [(max(0, int(a)), min(w, int(b))) for a, b in spans if b > a]


def overlay_row(w, h, base_row):
    alpha = base_row is None
    pixel = bytes(ACCENT) + (b"\xff" if alpha else b"")
    bpp = 4 if alpha else 3

    def row(y):
        line = bytearray(b"\x00" * (w * 4)) if alpha else bytearray(base_row(y))
        for a, b in play_spans(w, h, y):
            line[a * bpp:b * bpp] = pixel * (b - a)
        return bytes(line)
    return row


def write_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


def imageset(path, sizes, kind):
    images = []
    for scale, (w, h) in sizes:
        name = f"{kind}@{scale}.png"
        if kind == "back":
            write_png(os.path.join(path, name), w, h, background_row(w, h), False)
        elif kind == "front":
            write_png(os.path.join(path, name), w, h, overlay_row(w, h, None), True)
        else:
            write_png(os.path.join(path, name), w, h, overlay_row(w, h, background_row(w, h)), False)
        images.append({"filename": name, "idiom": "tv", "scale": scale})
    write_json(os.path.join(path, "Contents.json"), {"images": images, "info": INFO})


def imagestack(path, sizes):
    write_json(os.path.join(path, "Contents.json"),
               {"info": INFO, "layers": [{"filename": "Front.imagestacklayer"}, {"filename": "Back.imagestacklayer"}]})
    for layer, kind in (("Front", "front"), ("Back", "back")):
        lp = os.path.join(path, f"{layer}.imagestacklayer")
        write_json(os.path.join(lp, "Contents.json"), {"info": INFO})
        imageset(os.path.join(lp, "Content.imageset"), sizes, kind)


def main():
    write_json(os.path.join(ROOT, "Contents.json"), {"info": INFO})
    brand = os.path.join(ROOT, "App Icon & Top Shelf Image.brandassets")
    write_json(os.path.join(brand, "Contents.json"), {"info": INFO, "assets": [
        {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
        {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
        {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
        {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
    ]})
    imagestack(os.path.join(brand, "App Icon - App Store.imagestack"), [("1x", (1280, 768))])
    imagestack(os.path.join(brand, "App Icon.imagestack"), [("1x", (400, 240)), ("2x", (800, 480))])
    imageset(os.path.join(brand, "Top Shelf Image Wide.imageset"), [("1x", (2320, 720)), ("2x", (4640, 1440))], "flat")
    imageset(os.path.join(brand, "Top Shelf Image.imageset"), [("1x", (1920, 720)), ("2x", (3840, 1440))], "flat")
    print("Brand assets written to", os.path.normpath(ROOT))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reproduce the 2026-09-24 Atelier gradient measurements from its PNG capture.

The user confirmed consecutive swatches, black to white. Sample coordinates
are hand-selected stroke interiors in that specific 1920x2560 capture. Do not
apply them to another image. White is an endpoint, not a measurable stroke on
the white background. Photos provide visual context, not density calibration.
"""
import csv
import hashlib
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
SOURCE = ARTIFACTS / "gradient-atelier-reference.png"
SOURCE_SHA256 = "afad96f02df59fc86822ece39e1d1517232d11392cdbaaa6b49de28caca6e8cc"
GRAYS = [0, 80, 96, 104, 112, 128, 136, 144, 160, 170, 182, 192, 200, 208, 225, 255]
CENTERS = [110, 160, 190, 232, 270, 303, 347, 380, 416, 452, 490, 535, 572, 604, 639]


def main():
    source_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    if source_hash != SOURCE_SHA256:
        raise ValueError("Sample coordinates belong to a different screenshot")
    image = Image.open(SOURCE).convert("RGB")
    if image.size != (1920, 2560):
        raise ValueError("Expected the original 1920x2560 screenshot")
    rows = []
    for grade, gray in enumerate(GRAYS):
        if grade < len(CENTERS):
            y = CENTERS[grade]
            box = (500, y - 5, 530, y + 5)
            pixels = list(image.crop(box).getdata())
            if any(p not in [(0, 0, 0), (255, 255, 255)] for p in pixels):
                raise ValueError(f"Nonbinary pixel in grade {grade}")
            white = pixels.count((255, 255, 255))
            total = len(pixels)
            origin = "stroke interior"
        else:
            box, white, total, origin = None, 300, 300, "defined white endpoint"
        density = white / total
        count = int(density * 64 + .5)
        old_count = (gray * 64 + 127) // 255
        rows.append(dict(grade=grade, gray=gray, box=box, source=origin,
                         reference_white=white, reference_total=total,
                         atelier_black_pct=100 * (1 - density),
                         old_black_pct=100 * (1 - old_count / 64),
                         new_white_count=count, new_black_pct=100 * (1 - count / 64)))
    report = dict(source=str(SOURCE.relative_to(ROOT)),
                  sha256=source_hash, rows=rows,
                  limits="Small interior samples of one retained gradient; not an exact universal transfer function or physical reflectance measurement.")
    (ARTIFACTS / "gray-density-measurements.json").write_text(json.dumps(report, indent=2) + "\n")
    with (ARTIFACTS / "gray-density-measurements.csv").open("w") as out:
        writer = csv.DictWriter(out, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)
    print("White pixels per 8x8 tile:", [r["new_white_count"] for r in rows])
    print("Maximum error against sampled coverage (percentage points):",
          round(max(abs(r["new_black_pct"] - r["atelier_black_pct"]) for r in rows), 3))

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle

    fig, (detail, curve) = plt.subplots(1, 2, figsize=(11, 7), gridspec_kw={"width_ratios": [1, 2.4]})
    detail.imshow(image, interpolation="nearest")
    detail.set_xlim(420, 720)
    detail.set_ylim(675, 75)
    for r in rows[:-1]:
        x0, y0, x1, y1 = r["box"]
        detail.add_patch(Rectangle((x0, y0), x1-x0, y1-y0, fill=False, edgecolor="#e06020", linewidth=1))
        detail.text(700, (y0+y1)/2, str(r["grade"]), va="center", ha="center", fontsize=9)
    detail.set_title("Atelier samples\nNumbers are palette grades")
    detail.axis("off")
    grades = list(range(16))
    curve.plot(grades, [r["old_black_pct"] for r in rows], "o--", color="#9c6b30", label="Our previous density (0.11)")
    curve.plot(grades, [r["atelier_black_pct"] for r in rows], "o-", color="#202020", label="Atelier stroke samples")
    curve.plot(grades, [r["new_black_pct"] for r in rows], "x", markersize=8, color="#167b88", label="Calibrated dots (0.12)")
    curve.set(xlabel="Palette grade, black → white", ylabel="Black pixels (%)", ylim=(-3, 103), xlim=(-.4, 15.4))
    curve.set_xticks(grades)
    curve.grid(alpha=.2)
    curve.legend()
    curve.set_title("Matching dot coverage across the gradient")
    fig.text(.5, .015, "PNG dot counts, not photo brightness. White is a defined endpoint. Pattern texture and physical appearance may still differ.", ha="center", fontsize=9)
    fig.tight_layout(rect=(0, .04, 1, 1))
    fig.savefig(ARTIFACTS / "gray-density-comparison.png", dpi=160)
    plt.close(fig)


if __name__ == "__main__":
    main()

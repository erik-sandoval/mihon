# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "ai-edge-litert"]
# ///
"""
Live panel-detection preview — drop in any manga page, see what the reader's Guided view would do.

    uv run tools/panel-detection-preview.py <image-or-folder> [more ...]
    # or, deps already installed:  python tools/panel-detection-preview.py <image> ...
    # or on Windows: drag an image file onto tools/panel-preview.cmd

Renders a 3-up per page into tools/panel-previews/ and opens it:
    raw ML detections  |  + content-aware edge expansion  |  final zoom stops
(set PANEL_PREVIEW_OUT to render somewhere else.)

The whole chain mirrors the app: the same bundled model file, the same YoloPanelDecoder decode,
the same ContentAwarePanelExpander tuning, the same PanelPipeline geometry. Keep this file in sync
with those when they change — the constants and the algorithm shape are duplicated here on purpose
so the tool has no build step.
"""
from __future__ import annotations

import glob
import os
import sys
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, os.pardir, "app", "src", "main", "assets", "manga_panel_detector_int8.tflite")
OUT_DIR = os.environ.get("PANEL_PREVIEW_OUT", os.path.join(HERE, "panel-previews"))
MAX_DIM = 900          # app's MAX_DETECTION_DIMENSION
INPUT = 640            # model input size
CONFIDENCE = 0.25      # YoloPanelDecoder.DEFAULT_CONFIDENCE
NMS_IOU = 0.45
CONTAINMENT = 0.60
MIN_AREA_FRACTION = 0.008
MIN_SIDE_FRACTION = 0.08
PANEL_CLASS = 0


# --------------------------------------------------------------------------- #
# geometry primitive
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class R:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self):  return max(0.0, self.right - self.left)
    @property
    def height(self): return max(0.0, self.bottom - self.top)
    @property
    def cx(self):     return (self.left + self.right) / 2
    @property
    def cy(self):     return (self.top + self.bottom) / 2
    @property
    def area(self):   return self.width * self.height


FULL_PAGE = R(0.0, 0.0, 1.0, 1.0)


def _union(a: R, b: R) -> R:
    return R(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

def _ov(a0, a1, b0, b1): return max(0.0, min(a1, b1) - max(a0, b0))
def _vov(a: R, b: R): return a.top < b.bottom and b.top < a.bottom
def _hov(a: R, b: R): return a.left < b.right and b.left < a.right
def _rects_overlap(a: R, b: R):
    return a.left < b.right and a.right > b.left and a.top < b.bottom and a.bottom > b.top


# --------------------------------------------------------------------------- #
# model inference
# --------------------------------------------------------------------------- #
_interp = None

def _interpreter():
    global _interp
    if _interp is None:
        from ai_edge_litert.interpreter import Interpreter
        _interp = Interpreter(model_path=os.path.abspath(MODEL))
        _interp.allocate_tensors()
    return _interp


def _letterbox(im: Image.Image, size=INPUT):
    w, h = im.size
    s = min(size / w, size / h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    px, py = (size - nw) // 2, (size - nh) // 2
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(im.resize((nw, nh)), (px, py))
    return canvas, s, px, py


def _infer(im: Image.Image):
    it = _interpreter()
    inp, out = it.get_input_details()[0], it.get_output_details()[0]
    lb, s, px, py = _letterbox(im)
    arr = np.asarray(lb, dtype=np.float32)
    if inp["dtype"] == np.float32:
        data = (arr / 255.0)[None]
    else:
        scale, zero = inp["quantization"]
        q = np.clip(np.round(arr / 255.0 / (scale or 1.0)) + zero,
                    np.iinfo(inp["dtype"]).min, np.iinfo(inp["dtype"]).max)
        data = q.astype(inp["dtype"])[None]
    it.set_tensor(inp["index"], data)
    it.invoke()
    raw = it.get_tensor(out["index"]).astype(np.float32)
    if out["dtype"] != np.float32:
        oscale, ozero = out["quantization"]
        raw = (raw - ozero) * (oscale or 1.0)
    return raw, s, px, py


# --------------------------------------------------------------------------- #
# YoloPanelDecoder — decode()  (end-to-end [1,N,6] path + suppress + toPanels)
# --------------------------------------------------------------------------- #
def _iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
    return inter / ua if ua > 0 else 0.0

def _contained(inner, outer):
    ix = max(0.0, min(inner[2], outer[2]) - max(inner[0], outer[0]))
    iy = max(0.0, min(inner[3], outer[3]) - max(inner[1], outer[1]))
    ia = (inner[2]-inner[0])*(inner[3]-inner[1])
    return (ix * iy) / ia if ia > 0 else 0.0

def _suppress(boxes):
    kept = []
    for box in sorted(boxes, key=lambda b: -b[4]):
        if any(_iou(k, box) > NMS_IOU or _contained(box, k) > CONTAINMENT for k in kept):
            continue
        kept = [k for k in kept if _contained(k, box) <= CONTAINMENT]
        kept.append(box)
    return kept

def _merge_slivers(rects, min_side):
    GAP_PEN, AREA_PEN = 2.0, 1e-3
    slivers = [r for r in rects if r.width < min_side or r.height < min_side]
    real = [r for r in rects if not (r.width < min_side or r.height < min_side)]
    if not slivers:
        return real
    if not real:
        return []
    merged = list(real)
    for s in slivers:
        vert = s.width <= s.height
        def score(c):
            if vert:
                of = _ov(s.top, s.bottom, c.top, c.bottom) / max(1e-4, min(s.height, c.height))
                gap = max(0.0, max(s.left, c.left) - min(s.right, c.right))
            else:
                of = _ov(s.left, s.right, c.left, c.right) / max(1e-4, min(s.width, c.width))
                gap = max(0.0, max(s.top, c.top) - min(s.bottom, c.bottom))
            return of - gap * GAP_PEN - c.area * AREA_PEN
        hi = max(range(len(merged)), key=lambda i: score(merged[i]))
        merged[hi] = _union(merged[hi], s)
    return merged

def decode(raw, s, px, py, page_w, page_h):
    scale = min(INPUT / page_w, INPUT / page_h)
    panel_boxes, bubble_boxes = [], []
    for row in raw[0]:
        x1, y1, x2, y2, sc, cls = (float(v) for v in row[:6])
        if sc < CONFIDENCE:
            continue
        cs = INPUT if max(x1, y1, x2, y2) <= 1.5 else 1.0
        bx1 = (x1 * cs - px) / s / page_w
        by1 = (y1 * cs - py) / s / page_h
        bx2 = (x2 * cs - px) / s / page_w
        by2 = (y2 * cs - py) / s / page_h
        box = [min(bx1, bx2), min(by1, by2), max(bx1, bx2), max(by1, by2), sc]
        (panel_boxes if round(cls) == PANEL_CLASS else bubble_boxes).append(box)

    def to_rects(kept, min_area_frac, min_side_frac):
        min_area_px = min_area_frac * INPUT * INPUT
        out = []
        for b in kept:
            r = R(*b[:4])
            if r.width * page_w * scale * r.height * page_h * scale < min_area_px:
                continue
            if r.right <= r.left or r.bottom <= r.top:
                continue
            out.append(r)
        return _merge_slivers(out, min_side_frac) if min_side_frac > 0 else out

    panels = to_rects(_suppress(panel_boxes), MIN_AREA_FRACTION, MIN_SIDE_FRACTION)
    bubbles = to_rects(_suppress(bubble_boxes), 0.0, 0.0)
    return panels, bubbles


# --------------------------------------------------------------------------- #
# ContentAwarePanelExpander  (mirror of the Kotlin object)
# --------------------------------------------------------------------------- #
EDGE_DELTA = 24
GUTTER_MAX_DENSITY = 0.030
GUTTER_MAX_STD = 14.0
GUTTER_LUMA_LO = 65.0
GUTTER_LUMA_HI = 200.0
EDGE_AT_GUTTER_LINES = 3
MIN_GUTTER_FRACTION = 0.010
MAX_EXPAND_FRACTION = 0.16
NEIGHBOUR_OVERLAP_FRACTION = 0.015
NEIGHBOUR_MIN_PERP_OVERLAP = 0.35
CAPTION_GAP_MAX_FRACTION = 0.045
CAPTION_GAP_MAX_DENSITY = 0.06
CAPTION_MAX_HEIGHT_FRACTION = 0.11
CAPTION_STICKOUT_FRACTION = 0.06
CAPTION_MIN_COVER = 0.45


def _edge_density(vals):
    if len(vals) < 2:
        return 0.0
    return float(np.count_nonzero(np.abs(np.diff(vals)) > EDGE_DELTA)) / (len(vals) - 1)

def _is_gutter_line(vals):
    if len(vals) == 0 or _edge_density(vals) > GUTTER_MAX_DENSITY:
        return False
    if float(np.std(vals)) > GUTTER_MAX_STD:
        return False
    m = float(np.mean(vals))
    return m >= GUTTER_LUMA_HI or m <= GUTTER_LUMA_LO

def _span_overlap_frac(a0, a1, b0, b1):
    span = min(a1 - a0, b1 - b0)
    return (min(a1, b1) - max(a0, b0)) / span if span > 0 else 0.0


def _scan(start, step, max_move, axis_len, min_gut, neighbour_stop, line_at, on_gutter=None):
    if neighbour_stop is not None and (
        (step > 0 and neighbour_stop <= start) or (step < 0 and neighbour_stop >= start)
    ):
        return start
    probe, all_gutter = start, True
    for _ in range(EDGE_AT_GUTTER_LINES):
        probe += step
        if not (0 <= probe < axis_len) or not _is_gutter_line(line_at(probe)):
            all_gutter = False
            break
    if all_gutter:
        return on_gutter(start) if on_gutter else start

    gutter_run, c = 0, start
    for _ in range(max_move):
        c += step
        if not (0 <= c < axis_len):
            return c - step
        if neighbour_stop is not None and (
            (step > 0 and c >= neighbour_stop) or (step < 0 and c <= neighbour_stop)
        ):
            return neighbour_stop
        if _is_gutter_line(line_at(c)):
            gutter_run += 1
            if gutter_run >= min_gut:
                g = c - step * gutter_run
                return on_gutter(g) if on_gutter else g
        else:
            gutter_run = 0
    return start  # cap hit, no gutter → borderless, don't move


def _ink_extent(vals):
    d = np.abs(np.diff(vals))
    idx = np.nonzero(d > EDGE_DELTA)[0]
    return (int(idx[0]), int(idx[-1] + 1)) if len(idx) else (10**9, -1)


def _caption_extended_edge(gutter_line, step, axis_len, panel_l, panel_r,
                           stickout, max_gap, max_band_h, neighbour_stop, full_row_at):
    def in_bounds(y): return 0 <= y < axis_len
    def blocked(y): return neighbour_stop is not None and (
        (step > 0 and y >= neighbour_stop) or (step < 0 and y <= neighbour_stop))

    y, gap = gutter_line + step, 0
    while in_bounds(y) and _edge_density(full_row_at(y)) <= CAPTION_GAP_MAX_DENSITY and gap <= max_gap and not blocked(y):
        y += step; gap += 1
    if not in_bounds(y) or gap == 0 or gap > max_gap or blocked(y):
        return gutter_line

    cap_l, cap_r, band_h = 10**9, -1, 0
    while in_bounds(y) and not _is_gutter_line(full_row_at(y)) and band_h <= max_band_h and not blocked(y):
        rl, rr = _ink_extent(full_row_at(y))
        if rr >= rl:
            cap_l, cap_r = min(cap_l, rl), max(cap_r, rr)
        y += step; band_h += 1
    band_end = y
    if band_h < 2 or band_h > max_band_h or cap_r < cap_l:
        return gutter_line
    if in_bounds(band_end) and not _is_gutter_line(full_row_at(band_end)):
        return gutter_line
    pw = panel_r - panel_l
    if (cap_r - cap_l) >= CAPTION_MIN_COVER * pw and cap_l >= panel_l - stickout and cap_r <= panel_r + stickout:
        return band_end
    return gutter_line


FRAGMENT_NEAR_DUP_COVER = 0.42
FRAGMENT_STRIP_RATIO = 0.28
FRAGMENT_STRIP_MIN_SHARED = 0.6
FRAGMENT_MAX_GAP = 0.02


def _clampi(v, lo, hi):
    return min(max(int(v), lo), hi)


def _no_border_between(a, b, luma):
    h, w = luma.shape
    xo = min(a.right, b.right) - max(a.left, b.left)   # <0 => horizontal gap
    yo = min(a.bottom, b.bottom) - max(a.top, b.top)

    if xo > 0 and yo > 0 and (xo * yo) / min(a.area, b.area) >= FRAGMENT_NEAR_DUP_COVER:
        s_lo = _clampi(max(a.left, b.left) * w, 0, w - 1)
        s_hi = _clampi(min(a.right, b.right) * w, s_lo + 1, w)
        t_lo = _clampi(max(a.top, b.top) * h, 0, h - 1)
        t_hi = _clampi(min(a.bottom, b.bottom) * h, t_lo + 1, h)
        return not any(_is_gutter_line(luma[y, s_lo:s_hi]) for y in range(t_lo, t_hi))

    x_strip = min(a.width, b.width) <= FRAGMENT_STRIP_RATIO * max(a.width, b.width)
    y_strip = min(a.height, b.height) <= FRAGMENT_STRIP_RATIO * max(a.height, b.height)
    if (0 <= -xo <= FRAGMENT_MAX_GAP and x_strip
            and yo >= FRAGMENT_STRIP_MIN_SHARED * min(a.height, b.height)):
        lo = _clampi(min(a.right, b.right) * w, 0, w - 1)
        hi = _clampi(max(a.left, b.left) * w, lo + 1, w)
        s_lo = _clampi(max(a.top, b.top) * h, 0, h - 1)
        s_hi = _clampi(min(a.bottom, b.bottom) * h, s_lo + 1, h)
        return not any(_is_gutter_line(luma[s_lo:s_hi, x]) for x in range(lo, hi))
    if (0 <= -yo <= FRAGMENT_MAX_GAP and y_strip
            and xo >= FRAGMENT_STRIP_MIN_SHARED * min(a.width, b.width)):
        lo = _clampi(min(a.bottom, b.bottom) * h, 0, h - 1)
        hi = _clampi(max(a.top, b.top) * h, lo + 1, h)
        s_lo = _clampi(max(a.left, b.left) * w, 0, w - 1)
        s_hi = _clampi(min(a.right, b.right) * w, s_lo + 1, w)
        return not any(_is_gutter_line(luma[y, s_lo:s_hi]) for y in range(lo, hi))
    return False


FRAGMENT_SWALLOW_MAX = 0.15


def _overlap_area(a, b):
    return max(0.0, min(a.right, b.right) - max(a.left, b.left)) * max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))


def _merge_fragments(panels, luma):
    cur = list(panels)
    changed = True
    while changed:
        changed = False
        for i in range(len(cur)):
            for j in range(i + 1, len(cur)):
                if not _no_border_between(cur[i], cur[j], luma):
                    continue
                a, b = cur[i], cur[j]
                u = R(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
                if any(k != i and k != j and _overlap_area(u, p) > FRAGMENT_SWALLOW_MAX * p.area
                       for k, p in enumerate(cur)):
                    continue
                cur[i] = u
                del cur[j]
                changed = True
                break
            if changed:
                break
    return cur


def expand_panels(panels, luma):
    if not panels:
        return panels
    h, w = luma.shape
    panels = _merge_fragments(panels, luma)
    out = []
    for i, box in enumerate(panels):
        others = [q for j, q in enumerate(panels) if j != i]
        max_ex_x, max_ex_y = int(MAX_EXPAND_FRACTION * w), int(MAX_EXPAND_FRACTION * h)
        min_gut_x = max(2, int(MIN_GUTTER_FRACTION * w))
        min_gut_y = max(2, int(MIN_GUTTER_FRACTION * h))
        l = min(max(int(box.left * w), 0), w - 1)
        r = min(max(int(box.right * w), 1), w)
        t = min(max(int(box.top * h), 0), h - 1)
        b = min(max(int(box.bottom * h), 1), h)
        cx, cy = (box.left + box.right) / 2, (box.top + box.bottom) / 2
        ov_x, ov_y = int(NEIGHBOUR_OVERLAP_FRACTION * w), int(NEIGHBOUR_OVERLAP_FRACTION * h)

        def perp_x(o): return _span_overlap_frac(box.top, box.bottom, o.top, o.bottom) >= NEIGHBOUR_MIN_PERP_OVERLAP
        def perp_y(o): return _span_overlap_frac(box.left, box.right, o.left, o.right) >= NEIGHBOUR_MIN_PERP_OVERLAP
        xr = [int(o.left * w) for o in others if perp_x(o) and (o.left + o.right) / 2 > cx]
        xl = [int(o.right * w) for o in others if perp_x(o) and (o.left + o.right) / 2 < cx]
        yd = [int(o.top * h) for o in others if perp_y(o) and (o.top + o.bottom) / 2 > cy]
        yu = [int(o.bottom * h) for o in others if perp_y(o) and (o.top + o.bottom) / 2 < cy]
        stop_r = min(xr) + ov_x if xr else None
        stop_l = max(xl) - ov_x if xl else None
        stop_d = min(yd) + ov_y if yd else None
        stop_u = max(yu) - ov_y if yu else None

        cap_gap = int(CAPTION_GAP_MAX_FRACTION * h)
        cap_max_h = max(2, int(CAPTION_MAX_HEIGHT_FRACTION * h))
        stickout = int(CAPTION_STICKOUT_FRACTION * w)
        full_row = lambda y: luma[y, 0:w]
        def absorb(step, nstop):
            return lambda g: _caption_extended_edge(g, step, h, l, r, stickout, cap_gap, cap_max_h, nstop, full_row)

        col = lambda x: luma[t:b, x]
        row = lambda y: luma[y, l:r]
        nr = _scan(r, +1, max_ex_x, w, min_gut_x, stop_r, col)
        nl = _scan(l, -1, max_ex_x, w, min_gut_x, stop_l, col)
        nb = _scan(b, +1, max_ex_y, h, min_gut_y, stop_d, row, absorb(+1, stop_d))
        nt = _scan(t, -1, max_ex_y, h, min_gut_y, stop_u, row, absorb(-1, stop_u))
        out.append(R(max(0.0, nl / w), max(0.0, nt / h), min(1.0, nr / w), min(1.0, nb / h)))
    return out


# --------------------------------------------------------------------------- #
# PanelGapFiller  (unchanged from the app)
# --------------------------------------------------------------------------- #
def _region_has_ink(region, luma):
    h, w = luma.shape
    x0 = _clampi(region.left * w, 0, w - 1)
    x1 = _clampi(region.right * w, x0 + 1, w)
    y0 = _clampi(region.top * h, 0, h - 1)
    y1 = _clampi(region.bottom * h, y0 + 1, h)
    inky = 0
    for i in range(15):
        row = luma[y0 + (y1 - y0) * i // 15, x0:x1]
        if float(np.mean(row)) < 225 or int(np.count_nonzero(row < 110)) > len(row) // 12:
            inky += 1
    return inky >= 7


def gap_fill(panels, luma=None, min_area_frac=0.07, min_side_frac=0.12, max_aspect=4.0, grid=64, max_fills=8):
    if not panels:
        return panels
    n = grid
    covered = [False] * (n * n)
    for gy in range(n):
        cyy = (gy + 0.5) / n
        for gx in range(n):
            cxx = (gx + 0.5) / n
            covered[gy * n + gx] = any(p.left <= cxx <= p.right and p.top <= cyy <= p.bottom for p in panels)
    cell = 1.0 / (n * n)
    gaps = []
    for _ in range(max_fills):
        rect = _largest_empty(covered, n)
        if rect is None:
            break
        x0, y0, x1, y1, cells = rect
        if cells * cell < min_area_frac:
            break
        wf, hf = (x1 - x0 + 1) / n, (y1 - y0 + 1) / n
        for yy in range(y0, y1 + 1):
            for xx in range(x0, x1 + 1):
                covered[yy * n + xx] = True
        region = R(x0 / n, y0 / n, (x1 + 1) / n, (y1 + 1) / n)
        big = wf >= min_side_frac and hf >= min_side_frac
        aspect_ok = max(wf, hf) / min(wf, hf) <= max_aspect
        if big and (aspect_ok or (luma is not None and _region_has_ink(region, luma))):
            gaps.append(region)
    return panels + gaps if gaps else panels

def _largest_empty(covered, n):
    height = [0] * n
    best = None
    for y in range(n):
        for x in range(n):
            height[x] = 0 if covered[y * n + x] else height[x] + 1
        stack, x = [], 0
        while x <= n:
            hh = height[x] if x < n else 0
            while stack and height[stack[-1]] >= hh:
                bh = height[stack.pop()]
                left = 0 if not stack else stack[-1] + 1
                right = x - 1
                if bh > 0:
                    c = bh * (right - left + 1)
                    if best is None or c > best[4]:
                        best = (left, y - bh + 1, right, y, c)
            if x < n:
                stack.append(x)
            x += 1
    return best


# --------------------------------------------------------------------------- #
# PanelOrdering  (recursive X-Y cut; spread branch included)
# --------------------------------------------------------------------------- #
STRADDLE_TOLERANCE = 0.12
ORD_ROW_BAND = 0.12
MIN_SEAM_GAP = 0.01

def order(panels, rtl, is_spread):
    if len(panels) <= 1:
        return list(panels)
    if is_spread:
        seam = _spread_seam(panels)
        if seam is not None:
            rh = [p for p in panels if p.cx >= seam]
            lh = [p for p in panels if p.cx < seam]
            if rh and lh:
                first, second = (rh, lh) if rtl else (lh, rh)
                return _cut(first, rtl) + _cut(second, rtl)
    return _cut(panels, rtl)

def _spread_seam(panels):
    spans = sorted(((p.left, p.right) for p in panels), key=lambda s: s[0])
    end, best_mid, best = spans[0][1], None, MIN_SEAM_GAP
    for a, bb in spans[1:]:
        if a > end and a - end > best:
            best, best_mid = a - end, (end + a) / 2
        end = max(end, bb)
    return best_mid

def _cut(panels, rtl):
    if len(panels) <= 1:
        return list(panels)
    h = _find_cut(panels, False)
    if h:
        return _cut(h[0], rtl) + _cut(h[1], rtl)
    v = _find_cut(panels, True)
    if v:
        return (_cut(v[1], rtl) + _cut(v[0], rtl)) if rtl else (_cut(v[0], rtl) + _cut(v[1], rtl))
    by_top = sorted(panels, key=lambda p: p.top)
    rows = []
    for p in by_top:
        if rows and p.top - rows[-1][0].top <= ORD_ROW_BAND:
            rows[-1].append(p)
        else:
            rows.append([p])
    res = []
    for rrow in rows:
        res += sorted(rrow, key=lambda p: -p.left if rtl else p.left)
    return res

def _find_cut(panels, vertical):
    s = (lambda p: p.left) if vertical else (lambda p: p.top)
    e = (lambda p: p.right) if vertical else (lambda p: p.bottom)
    max_end = max(e(p) for p in panels)
    for line in sorted(set([e(p) for p in panels] + [s(p) for p in panels])):
        if line >= max_end:
            continue
        first, second, valid = [], [], True
        for p in panels:
            sp, ep = s(p), e(p)
            if ep <= line:
                first.append(p)
            elif sp >= line:
                second.append(p)
            else:
                if min(ep - line, line - sp) / max(ep - sp, 1e-4) > STRADDLE_TOLERANCE:
                    valid = False
                    break
                (first if (line - sp) >= (ep - line) else second).append(p)
        if valid and first and second:
            return (first, second)
    return None


# --------------------------------------------------------------------------- #
# PanelPlanner.mergeSmall  (MANGA config: enableDivide = false)
# --------------------------------------------------------------------------- #
SMALL_AREA_FRAC = 0.05
ADJ_GAP = 0.05
ADJ_OVERLAP = 0.4
MAX_MERGE = 2
MAX_MERGED_W = 0.55
MAX_MERGED_H = 0.45

def plan(ordered):
    regions, i = [], 0
    while i < len(ordered):
        cur = ordered[i]
        if cur.area >= SMALL_AREA_FRAC:
            regions.append(cur)
            i += 1
            continue
        u, j, count, d = cur, i, 1, None
        while (j + 1 < len(ordered) and count < MAX_MERGE and ordered[j + 1].area < SMALL_AREA_FRAC):
            nxt = ordered[j + 1]
            step = _adjacency_dir(ordered[j], nxt)
            if step is None or (d is not None and step != d):
                break
            d = d or step
            cand = _union(u, nxt)
            if cand.width > MAX_MERGED_W or cand.height > MAX_MERGED_H:
                break
            if any(idx < i or idx > j + 1 for idx, p in enumerate(ordered) if _rects_overlap(cand, p)):
                break
            u, j, count = cand, j + 1, count + 1
        regions.append(u)
        i = j + 1
    return regions

def _adjacency_dir(a, b):
    vo = _ov(a.top, a.bottom, b.top, b.bottom) / max(1e-4, min(a.height, b.height))
    ho = _ov(a.left, a.right, b.left, b.right) / max(1e-4, min(a.width, b.width))
    hg = max(0.0, max(a.left, b.left) - min(a.right, b.right))
    vg = max(0.0, max(a.top, b.top) - min(a.bottom, b.bottom))
    side = vo >= ADJ_OVERLAP and hg <= ADJ_GAP
    stack = ho >= ADJ_OVERLAP and vg <= ADJ_GAP
    if side and stack:
        return "H" if hg <= vg else "V"
    return "H" if side else ("V" if stack else None)


# --------------------------------------------------------------------------- #
# PanelPipeline  (current revision)
# --------------------------------------------------------------------------- #
BASE_MARGIN = 0.025
BUBBLE_CLEARANCE = 0.06
SPREAD_ASPECT_MIN = 1.15
MAX_EDGE_EXTENSION = 0.02

def _contains_center(p, b): return p.left <= b.cx <= p.right and p.top <= b.cy <= p.bottom
def _overlaps(p, b): return b.left < p.right and b.right > p.left and b.top < p.bottom and b.bottom > p.top

def zoom_regions(panels, bubbles, page_w, page_h, rtl, luma=None):
    is_spread = page_w / page_h >= SPREAD_ASPECT_MIN
    filled = gap_fill(panels, luma=luma)
    ordered = order(filled, rtl, is_spread)
    planned = plan(ordered)
    if len(planned) < 2:
        return [FULL_PAGE]
    return _close_gaps(_extend_edges(_pad(planned, bubbles)))

def _capped_margin(size, gap):
    return size * BASE_MARGIN if gap is None else min(size * BASE_MARGIN, gap / 2)

def _pad(panels, bubbles):
    unclaimed = [b for b in bubbles if not any(_contains_center(p, b) for p in panels)]
    out = []
    for p in panels:
        gl = [p.left - x.right for x in panels if x is not p and _vov(x, p) and x.right <= p.left]
        gr = [x.left - p.right for x in panels if x is not p and _vov(x, p) and x.left >= p.right]
        gt = [p.top - x.bottom for x in panels if x is not p and _hov(x, p) and x.bottom <= p.top]
        gb = [x.top - p.bottom for x in panels if x is not p and _hov(x, p) and x.top >= p.bottom]
        left = p.left - _capped_margin(p.width, min(gl) if gl else None)
        right = p.right + _capped_margin(p.width, min(gr) if gr else None)
        top = p.top - _capped_margin(p.height, min(gt) if gt else None)
        bottom = p.bottom + _capped_margin(p.height, min(gb) if gb else None)
        for b in bubbles:
            if not (_contains_center(p, b) or (b in unclaimed and _overlaps(p, b))):
                continue
            cxp, cyp = b.width * BUBBLE_CLEARANCE, b.height * BUBBLE_CLEARANCE
            left, top = min(left, b.left - cxp), min(top, b.top - cyp)
            right, bottom = max(right, b.right + cxp), max(bottom, b.bottom + cyp)
        out.append(R(max(left, 0.0), max(top, 0.0), min(right, 1.0), min(bottom, 1.0)))
    return out

def _extend_edges(panels):
    out = []
    for p in panels:
        left, top, right, bottom = p.left, p.top, p.right, p.bottom
        if left > 0 and not any(x is not p and _vov(x, p) and x.left < left for x in panels):
            left = max(left - MAX_EDGE_EXTENSION, 0.0)
        if top > 0 and not any(x is not p and _hov(x, p) and x.top < top for x in panels):
            top = max(top - MAX_EDGE_EXTENSION, 0.0)
        if right < 1 and not any(x is not p and _vov(x, p) and x.right > right for x in panels):
            right = min(right + MAX_EDGE_EXTENSION, 1.0)
        if bottom < 1 and not any(x is not p and _hov(x, p) and x.bottom > bottom for x in panels):
            bottom = min(bottom + MAX_EDGE_EXTENSION, 1.0)
        out.append(R(left, top, right, bottom))
    return out

def _close_gaps(panels):
    out = []
    for p in panels:
        left, top, right, bottom = p.left, p.top, p.right, p.bottom
        c = [x for x in panels if x is not p and _vov(x, p) and x.left > p.left]
        if c and min(c, key=lambda x: x.left).left > right:
            right += (min(c, key=lambda x: x.left).left - right) / 2
        c = [x for x in panels if x is not p and _vov(x, p) and x.right < p.right]
        if c and max(c, key=lambda x: x.right).right < left:
            left -= (left - max(c, key=lambda x: x.right).right) / 2
        c = [x for x in panels if x is not p and _hov(x, p) and x.top > p.top]
        if c and min(c, key=lambda x: x.top).top > bottom:
            bottom += (min(c, key=lambda x: x.top).top - bottom) / 2
        c = [x for x in panels if x is not p and _hov(x, p) and x.bottom < p.bottom]
        if c and max(c, key=lambda x: x.bottom).bottom < top:
            top -= (top - max(c, key=lambda x: x.bottom).bottom) / 2
        out.append(R(left, top, right, bottom))
    return out


# --------------------------------------------------------------------------- #
# render
# --------------------------------------------------------------------------- #
def _font(size):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except Exception:
            pass
    return ImageFont.load_default()

def _draw(img, boxes, colour, prefix, extra=None, extra_colour=(255, 210, 0)):
    im = img.convert("RGB").copy()
    d = ImageDraw.Draw(im)
    w, h = im.size
    f = _font(max(15, w // 52))
    for r in (extra or []):
        d.rectangle([r.left * w, r.top * h, r.right * w, r.bottom * h], outline=extra_colour, width=2)
    for i, r in enumerate(boxes):
        bb = [r.left * w, r.top * h, r.right * w, r.bottom * h]
        d.rectangle(bb, outline=colour, width=max(3, w // 240))
        d.text((bb[0] + 3, bb[1] + 1), f"{prefix}{i}", fill=colour, font=f)
    return im


def process(path, rtl=True):
    im = Image.open(path).convert("RGB")
    if max(im.size) > MAX_DIM:
        s = MAX_DIM / max(im.size)
        im = im.resize((round(im.width * s), round(im.height * s)))
    w, h = im.size

    raw, s, px, py = _infer(im)
    panels, bubbles = decode(raw, s, px, py, w, h)
    luma = np.asarray(im.convert("L"), dtype=np.int16)
    expanded = expand_panels(panels, luma)
    stops = zoom_regions(expanded, bubbles, w, h, rtl, luma)

    a = _draw(im, panels, (0, 200, 255), "d", extra=bubbles)
    b = _draw(im, expanded, (255, 60, 60), "x")
    c = _draw(im, stops, (80, 255, 120), "")

    gap = 20
    canvas = Image.new("RGB", (w * 3 + gap * 2, h + 46), (18, 18, 18))
    labels = [((0, 200, 255), "raw ML detections (panels + bubbles)"),
              ((255, 90, 90), "+ content-aware edge expansion"),
              ((80, 255, 120), f"final zoom stops ({len(stops)})")]
    for k, (img, (col, label)) in enumerate(zip((a, b, c), labels)):
        canvas.paste(img, (k * (w + gap), 46))
        ImageDraw.Draw(canvas).text((k * (w + gap) + 6, 12), label, fill=col, font=_font(22))

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, f"{os.path.splitext(os.path.basename(path))[0]}.png")
    canvas.save(out)
    print(f"  {os.path.basename(path)}: {len(panels)} panels, {len(bubbles)} bubbles -> {len(stops)} stops")
    return out


def main():
    args = [a for a in sys.argv[1:] if a != "--ltr"]
    rtl = "--ltr" not in sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(1)
    paths = []
    for a in args:
        if os.path.isdir(a):
            for e in ("*.jpg", "*.jpeg", "*.png", "*.webp"):
                paths += glob.glob(os.path.join(a, e))
        elif os.path.isfile(a):
            paths.append(a)
        else:
            print(f"  not found: {a}")
    outs = []
    for p in sorted(paths):
        try:
            outs.append(process(p, rtl))
        except Exception as e:
            print(f"  {p}: FAILED {e!r}")
    if outs and hasattr(os, "startfile"):
        try:
            os.startfile(outs[0])  # noqa: S606 — open the first result for the user
        except Exception:
            pass
    elif outs:
        print(f"\nwrote {len(outs)} preview image(s); open: {outs[0]}")


if __name__ == "__main__":
    main()

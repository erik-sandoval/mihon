# Manual Panel Editor

## Overview

A dedicated screen for correcting a page's panel-by-panel boundaries by
hand — resize, move, add, remove, merge, and split panels — when the
automatic detector gets a page wrong (title/cast-intro pages, hairline-gap
splits the heuristics don't catch, an interior gap between two panels that
should favor one side over an even split, etc.). This was an explicit
non-goal of the original panel-to-panel reader design
(`2026-08-16-panel-to-panel-reader-design.md`: "No manual panel-editing UI
(correcting bad detections) in v1") — this is that v2.

A second, equally important goal: the corrected data must be stored in a
form usable later as labeled training data for fine-tuning the on-device
detection model (already a deferred item — see memory
`panel_model_finetuning_deferred.md`), not just as a per-page override.

## Goals

- A separate "Edit Panels" screen, reached from a new action in the
  existing long-press page-actions sheet, showing the full page with
  editable panel boxes.
- Resize (drag edges/corners), move (drag inside), add (draw new), remove
  (delete), merge (combine two into their union), split (divide one in
  two).
- Saving persists the edited list as a manual override for that exact
  page, and returns to the reader with the page immediately reflecting the
  edit (reusing the `RefreshPanelDetection` event/`forceFirstStop`
  mechanism built for the full-page override feature).
- The manual override is **not** invalidated by a `DETECTOR_VERSION` bump —
  it's a user annotation, not a cached detection outcome (same principle
  already established for `panel_full_page_override`).
- The manual override takes priority over the full-page override, which
  takes priority over normal detection. Saving a manual edit clears any
  full-page override on that page (the manual list is strictly more
  specific).
- The corrected panel list is stored close to the format the detector
  model itself trains on (YOLO-style: normalized `class x_center y_center
  width height` — a trivial conversion from `PanelRect`), clearly
  distinguished from raw/cached detections, and paired with a **snapshot
  of the page image** copied to local storage at correction time — so a
  training export later doesn't depend on the chapter still being
  downloaded.

## Non-goals

- No bubble/text-box correction — the editor is panels only. A future
  training export would pair corrected panel labels with whatever bubble
  boxes the original raw detection produced for that page (a known,
  acceptable limitation, not silently glossed over).
- No manual reading-order editing — edited panels still get ordered via
  the existing `PanelOrdering.order()`, same as detected ones. If this
  turns out to be needed later (an edit scrambles the natural geometric
  order in a way the existing left-to-right/top-to-bottom ordering logic
  can't resolve), that's a follow-up, not part of this pass.
- No actual training/export script or pipeline in this pass — that's the
  already-deferred fine-tuning work (needs a Python 3.11/3.12 env). This
  feature only needs to make sure nothing about how the data is stored
  would block that later.
- No editing UI reuse of the live panel-by-panel viewer's touch stack —
  see Architecture below for why.

## Architecture

### Why an isolated screen, not inline editing

CLAUDE.md documents a full session where layering new touch gestures
(pinch-zoom) onto the *live* panel-by-panel viewer kept breaking in
cascading ways, because so many independent components already watch that
same touch stream: `Pager`'s own `GestureDetector`, the child
`SubsamplingScaleImageView`'s pinch/pan/double-tap, `DirectionalViewPager`,
`GestureDetectorWithLongTap`. That work was eventually reverted rather than
converged. A manual editor is a *second* new gesture surface on the same
page content, so it inherits that same risk if bolted onto the live
viewer.

Instead: a separate screen (`PanelEditorActivity` or a Compose
destination — implementation detail for the plan) that shows a **static**
decoded page bitmap with **its own**, simple, from-scratch touch handling
(drag a handle, tap to select, draw a new rect) — zero relationship to
`Pager`, `SubsamplingScaleImageView`, `PagerPageHolder`, or any of the
existing reader gesture stack. Entered from and returns to the reader via
normal Android navigation, not an overlay on top of the live viewer.

### Data model

New table, separate from `panel_cache` (which the existing
`DETECTOR_VERSION`/`image_hash`-keyed cache uses):

```sql
CREATE TABLE panel_manual_override(
    chapter_id INTEGER NOT NULL,
    page_index INTEGER NOT NULL,
    panels_json TEXT NOT NULL,
    image_snapshot_path TEXT NOT NULL,
    corrected_at INTEGER NOT NULL,
    PRIMARY KEY (chapter_id, page_index),
    FOREIGN KEY(chapter_id) REFERENCES chapters (_id)
    ON DELETE CASCADE
);
```

No `detector_version` or `image_hash` column — deliberate, so a version
bump or re-detection never touches this table (mirrors
`panel_full_page_override`'s same design choice, documented there).

`panels_json` stores the corrected panel list as `PanelPageData` (the same
serialized shape `panel_cache` already uses) for internal consistency —
*not* pre-converted to YOLO format at write time. A later export step
converts `PanelRect(left, top, right, bottom)` → YOLO's
`x_center, y_center, width, height` (trivial arithmetic) at export time,
against whatever the export tooling's actual directory/manifest
conventions turn out to need — not worth guessing that shape now, months
before the export tool exists.

`image_snapshot_path` points to a copy of the page's decoded image, saved
under the app's own files directory (e.g.
`context.filesDir/panel_training_snapshots/{chapterId}_{pageIndex}.jpg`)
at the moment a correction is saved — so the image is guaranteed available
for a training export later regardless of whether the source chapter is
still downloaded. Written once per corrected page (re-saving an edit to
the same page overwrites the same snapshot file, doesn't accumulate
duplicates).

### Repository

`PanelManualOverrideRepository` (mirrors `PanelCacheRepository`'s and
`PanelFullPageOverrideRepository`'s shape): `get`, `save` (writes the DB
row *and* copies the image snapshot file), `remove` (deletes the DB row
*and* the snapshot file — "Reset to detected" in the editor UI).

### Detector integration

`PanelDetector.detect()`'s override-check order (highest priority first):

1. `panel_manual_override` present → return its panel list directly, no
   `PanelPipeline` padding/margin logic applied (the user already placed
   these boxes exactly where they want them) — only `PanelOrdering.order()`
   runs, to keep reading order automatic.
2. `panel_full_page_override` present → return `[PanelRect.FULL_PAGE]`
   (existing behavior, unchanged).
3. Otherwise → normal cached/fresh detection (existing behavior,
   unchanged).

Saving a manual override clears any full-page override on the same page
(via `PanelFullPageOverrideRepository.removeOverride`) — the two are
mutually exclusive in effect, and the manual list is the more specific of
the two.

### Editor screen UI

Shows the full page image (not zoomed to any single stop) with the
*currently effective* panel list for that page drawn as boxes — whichever
of manual override / full-page-override-expanded-back-to-detected /
fresh detection currently applies. Interactions:

- **Select**: tap a box, highlights it.
- **Resize**: drag a corner/edge handle on the selected box.
- **Move**: drag inside a selected box.
- **Add**: a mode toggle, then drag out a new rectangle.
- **Delete**: button, removes the selected box.
- **Merge**: select two boxes (multi-select mode), "Merge" combines them
  into their union rect.
- **Split**: select one box, "Split" shows a draggable divider — vertical
  by default if the panel is wider than tall, horizontal if taller than
  wide (a toggle switches orientation), starting at the panel's centre —
  confirming divides it into two.
- **Save**: persists the current box list as the manual override, copies
  the image snapshot, and returns to the reader.
- **Reset to detected**: clears any manual override (and its snapshot) for
  this page, reverting to normal detection.
- **Cancel** / back: discards in-progress changes, no persistence.

### Return-to-reader flow

Reuses the existing `ReaderViewModel.Event.RefreshPanelDetection`
mechanism (built for the full-page-override toggle): on save, the editor
signals the reader (e.g. an activity result) with the edited page, the
reader calls `panelFullPageOverrideRepository`-equivalent refresh for the
manual override, then `(state.viewer as? PanelByPanelViewer)
?.refreshPanelDetection(page, forceFirstStop = true)` — treating "just
finished editing this page's panels" the same as "just removed the
full-page override": a fresh entry into (corrected) real panels, landing
on stop 0 rather than anchoring to wherever the reader happened to be
before entering the editor.

## Testing

- `PanelManualOverrideRepository`: unit-testable the same way as
  `PanelFullPageOverrideRepository` (DB round-trip), plus the file-copy
  side effect (verify the snapshot file exists after `save`, is removed
  after `remove`).
- `PanelDetector`'s override-priority order (manual > full-page > normal):
  unit-testable with fakes for both repositories, same style as existing
  `PanelDetector`-adjacent tests.
- Editor screen's box-manipulation logic (resize/move/add/merge/split
  arithmetic): unit-testable as pure functions operating on
  `List<PanelRect>`, independent of the actual touch/gesture code, the
  same way `PanelPlanner`/`PanelPipeline` logic is tested today without
  needing a real touch event.
- The touch handling itself and the full save→return-to-reader flow need
  on-device verification (no existing UI test infra for this reader) —
  same as everything else in this feature area this session.

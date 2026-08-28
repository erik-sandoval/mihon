# Panel Ground-Truth Labeler — Design

## Purpose

A standalone, self-contained local HTML tool (not part of the Mihon app or repo build) for turning
flagged panel-detection pages — exported by `PanelFlagExporter` in the reader — into labeled
ground truth for the deferred panel-model fine-tuning project (see the
`panel-model-finetuning-deferred` memory). It loads a flagged page's image and the model's raw
detections, lets the user correct them by dragging/adding/removing boxes, and saves the corrected
boxes as ground truth — never touching the original `detection.json`.

This tool lives outside the app's Gradle build entirely: one `.html` file the user opens directly
in a browser, no server, no build step.

## Constraints that shaped this design

The user's primary device is **Firefox for Android**. Two Firefox-specific facts drove several
decisions here (verified via web search, not assumed):

- Firefox has no support for the File System Access API (`showDirectoryPicker`, live
  read/write handles) on any platform — Mozilla has taken an explicit standards position against
  it. Desktop Chrome/Edge/Opera support it.
- Firefox for Android additionally has no support for folder selection at all
  (`<input webkitdirectory>` is unimplemented there, though desktop Firefox does support it,
  read-only). The only thing that works everywhere, including Firefox Android, is a plain
  multi-file picker (`<input type="file" multiple>`).

Because of the second point, the exporter (already changed, see below) now writes each flagged
page as two flat files sharing a filename stem (`<stem>.jpg` + `<stem>.json`) directly in one
`panel_flags/` directory, rather than a per-page subfolder — a flat multi-file selection has no
folder-structure information at all, so pairing must be possible from filenames alone.

## Exporter changes (already implemented, ahead of this doc)

`PanelFlagExporter.panelFlagStem`/`panelGoodFlagStem` replace the old `panelFlagDirName`/
`panelGoodFlagDirName` — same sanitization, same content, just used as a shared filename stem
instead of a directory name. `panel_flags/` is now flat for both failure-reason and
`good_example` flags (the `good/` subfolder was removed). This was a breaking layout change, but
every export that existed under the old per-page-subfolder layout has already been renamed
on-device to match the new flat scheme (one-off `adb shell` cleanup, not app code) — so the tool
only needs to support the flat layout, not both.

## Loading a batch

Two loading paths, chosen automatically by feature detection — never ask the user which to use:

1. **Folder picker, where supported** (desktop Chrome/Edge via `showDirectoryPicker()`; desktop
   Firefox via `<input webkitdirectory>`, read-only). Recursively walks the picked folder.
2. **Plain multi-file picker, everywhere else** (Firefox for Android, and as a manual fallback
   button always visible even where the folder picker exists, since a phone's file manager may
   only let you multi-select within one folder view at a time). An "Add more files" action lets
   the user run the picker repeatedly to accumulate a larger batch across several picker
   invocations — necessary on mobile, where the OS picker can't reach nested folders in one pass.

Either path ends at the same in-memory list of `File`/`FileSystemFileHandle` objects, processed
identically from that point on.

### Pairing files into pages

Group the flat file list by filename stem (everything before the last `.`) — every file the tool
cares about is `<stem>.jpg`/`.png`/`.webp` or `<stem>.json`, regardless of which loading path
found it, so this works identically whether the batch came from a real folder picker or a flat
multi-select with zero folder information (the Firefox Android case).

A group is a valid page once it has exactly one image file and one `.json` file; anything else
(an orphaned image with no JSON, or vice versa) is skipped and listed as a warning rather than
silently dropped.

## Editing UI

- Image fills the available viewport, scaled to fit, with pinch-to-zoom and pan implemented in
  the tool itself (not relying on browser page-zoom, so drag coordinates stay correct under any
  zoom level) — needed for precise corner placement on a phone screen.
- Starting boxes come from `detection.json`'s `rawDetections` (both classes), not `finalPanels` —
  `finalPanels` are the reader's padded display regions, not tight boxes, so they're a worse
  starting scaffold for ground truth than the raw model candidates (which also include
  sub-threshold near-misses worth confirming or rejecting).
- Each box renders with 4 corner-drag handles (resize) and a draggable body (move); boxes are
  color-coded by class (panel vs. text/bubble).
- A class toggle (panel/text) applies to newly-drawn boxes; tapping an existing box surfaces a
  small delete control and a way to flip its class.
- Draw a new box by dragging on empty canvas space — covers a fully-missed caption that scored
  ~0 and so has no starting box at all.
- Prev/Next steps through the batch; a progress indicator and a per-page "labeled" mark (has a
  saved `<stem>.ground_truth.json` in this session or on disk) let the user resume a partial pass.

## Saving

- **Live write-back**, only when the batch was loaded via a folder picker that grants a writable
  handle (`showDirectoryPicker()` — Chrome/Edge desktop only): writes `<stem>.ground_truth.json`
  directly next to the source files. Autosaves on navigating to the next/previous page, plus an
  explicit "Save" button.
- **Download fallback**, everywhere else (this covers Firefox entirely, both desktop and mobile,
  and any multi-file-picker session even on Chrome): "Save" triggers a browser download of
  `<stem>.ground_truth.json`, which the user drops back next to the source files themselves.
- Format:
  ```json
  {
    "panels": [{"left":0,"top":0,"right":1,"bottom":1}, ...],
    "texts": [{"left":0,"top":0,"right":1,"bottom":1}, ...],
    "labeledAt": 1735300000000
  }
  ```
  Same normalized `[0,1]` `{left,top,right,bottom}` convention as the Kotlin side's `PanelRect`,
  for later interop. `detection.json` is never modified.

## Explicitly out of scope

- No upload anywhere — everything is local to the browser tab, consistent with this being a
  personal/offline tool for the user's own library images.
- No re-running the ML model in the browser; the tool only edits coordinates already captured by
  the app.
- No handling of double-page spreads or any manga-specific reading-direction logic — this is a
  generic box editor over one image at a time.
- No account/sync/multi-device state beyond whatever the chosen browser's own downloads/file
  system already provides.

# Layout grid (LAYOUT_WIDE, 13.33 × 7.5 in)

## Fixed grid — use these numbers, do not improvise positions
- Outer margins: left/right **0.6 in**, top **0.5 in**, bottom **0.5 in**. Safe content box: x 0.6 → 12.73, y 0.5 → 7.0.
- Title zone (content slides): `{ x: 0.6, y: 0.45, w: 12.13, h: 0.9 }`, valign `middle`.
- Body zone below the title: starts at **y 1.55**, ends at **y 6.9** (5.35 in tall).
- Gutters between columns/cards: **0.3 in**.
- Column widths inside the 12.13 in body width:
  - 2 columns: 5.915 each (x 0.6 and 6.815)
  - 3 columns: 3.843 each (x 0.6, 4.743, 8.886)
  - 4 columns: 2.808 each (x 0.6, 3.708, 6.816, 9.924)
- Split layouts (image + text): image 5.9 wide, text 5.93 wide, 0.3 gutter.
- Every element's right edge (x + w) ≤ 12.73 and bottom edge (y + h) ≤ 7.0. Check this for every addText,
  addShape, addImage, addChart and addTable before writing it.

## Alignment
- Left edges of all body elements on a slide share the same x. Cards in a row share y and h.
- Centered layouts (COVER, BIG_STATEMENT, BIG_NUMBER, QUOTE) center horizontally on 6.665 in.
- Keep the title at the same position on every content slide — the eye should never search for it.

## Whitespace
- No region larger than ~30 % of the body zone may be empty on a content slide unless the pattern is
  deliberately minimal (BIG_STATEMENT, QUOTE, BIG_NUMBER).
- Never fill space with decoration; grow the real content (larger type, larger chart, larger image) instead.

## Pattern geometry (starting points)
- COVER: dark background; title `{x:0.9, y:2.3, w:11.5, h:1.6}` 48–56 pt; subtitle `{x:0.9, y:4.0, w:11.5, h:0.8}` 20 pt.
- BIG_NUMBER: number `{x:0.6, y:1.9, w:12.13, h:2.2}` 96 pt centered; label `{y:4.2, h:0.7}` 22 pt; support `{y:5.0, h:0.8}` 16 pt.
- METRIC_GRID: 3–4 equal cards in one row at y 2.0, h 3.2; number 44–54 pt, label 14 pt inside each card.
- PROCESS / TIMELINE: 3–5 equal steps across the body width; connectors are thin lines or small arrows
  between cards, never overlapping card text.
- COMPARISON / BEFORE_AFTER: two equal panels (5.915 in) with distinct header fills; same row count on both sides.
- CHART_FOCUS: chart `{x:0.6, y:1.55, w:8.3, h:5.3}`; one insight callout `{x:9.2, y:1.55, w:3.53, h:5.3}`.
- FULL_BLEED_IMAGE: image `{x:0, y:0, w:13.33, h:7.5}` with `sizing:{type:'cover', w:13.33, h:7.5}`; text on an
  overlay box with ≥ 70 % opacity fill.

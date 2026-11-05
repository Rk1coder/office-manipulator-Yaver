# pptxgenjs craft (what renders reliably)

## Structure the script
- Define the palette, fonts and grid as constants once at the top (`const C = {...}`, `const G = {...}`)
  and reference them everywhere — consistency comes from code, not from repeating literals.
- Write small helpers for repeated pieces (`addTitle(slide, text)`, `addCard(slide, x, y, w, h, heading, body)`)
  so every slide uses identical positions, sizes and margins.
- Build a fresh options object for every call (helpers that return `{ ... }` literals are fine).

## Shapes and cards
- Cards: `slide.addShape(pptx.ShapeType.roundRect, { x, y, w, h, fill: { color: C.card }, line: { color: C.border, width: 0.75 }, rectRadius: 0.08 })`
  then the text in a box inset ≥ 0.15 in on each side.
- Subtle depth is allowed: `shadow: { type: 'outer', blur: 6, offset: 2, angle: 90, color: '000000', opacity: 0.12 }`.
  No heavy shadows, no bevels, no 3-D.
- Connectors/arrows: `pptx.ShapeType.line` with `line: { color, width: 1.5, endArrowType: 'triangle' }`,
  placed in the gutter between cards — never across text.
- Numbered step markers: small circles (`ShapeType.ellipse`, 0.5 × 0.5 in) with the number centered.

## Text
- Rich runs: `slide.addText([{ text: 'Önemli ', options: { bold: true, color: C.accent } }, { text: 'devamı' }], {...})`.
- Bullets: `bullet: true` (or `{ code: '2022' }`) with `indentLevel` for hierarchy; one paragraph per bullet.
- Do not rely on `fit`/`autoFit`; size the box (see typography guide).

## Backgrounds and notes
- `slide.background = { color: C.dark }` for dark slides; set it explicitly on every slide.
- Put the presenter's script in `slide.addNotes('...')` using the plan's speakerNotes.

## Things that break rendering — never do these
- '#' in colors, 8-digit colors, shared option objects, `await` inside forEach without Promise handling.
- Shapes or text with negative x/y or extending beyond 13.33 × 7.5.
- Font sizes as strings, w/h of 0, empty text runs, `null` in chart values.
- Emoji or icon fonts for icons (they render as boxes) — draw simple shapes instead.

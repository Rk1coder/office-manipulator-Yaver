# Typography and text fit (the #1 cause of broken slides is text that does not fit)

## Font pairing — the deck's typographic voice
Use the DESIGN DIRECTION's fontPairing; if none is given, pick the row whose mood fits the topic.
Only these faces are installed everywhere the deck is rendered — any other font is silently replaced.
| Pairing | Headings | Body | Display (big numbers, eyebrows) | Mood |
|---|---|---|---|---|
| EDITORIAL | Georgia | Calibri | Georgia (italic for quotes) | storytelling, nature, history, culture |
| BOLD_IMPACT | Arial Black | Calibri | Arial Black | punchy, data-heavy, marketing, pitch |
| MODERN | Trebuchet MS | Calibri | Trebuchet MS bold | tech, product, startup, education |
| WARM_HUMANIST | Candara | Corbel | Candara bold | friendly, health, community, food |
| ELEGANT | Constantia | Corbel | Constantia (italic for quotes) | premium, art, law, finance |
| CLASSIC | Cambria | Calibri | Cambria | neutral corporate, last resort |
Define them once: `const F = { head: 'Georgia', body: 'Calibri', display: 'Georgia' };` and set `fontFace`
on **every** addText call — an unset fontFace falls back to the theme font and breaks the voice.

## Type devices that make a deck look designed (use 2–3 of them consistently)
- **Eyebrow label** above the slide title: 12–13 pt, `F.display` or `F.body` bold, UPPERCASE, `charSpacing: 3`,
  accent color — e.g. "BÖLÜM 2 · İLETİŞİM". Same position on every content slide.
- **Weight contrast:** heading bold in `F.head`, body regular in `F.body`; a lead sentence (first line of the
  body, 20–22 pt) before smaller detail text.
- **Display numbers:** 88–120 pt in `F.display`, accent color, with a 14–16 pt label set in `F.body`;
  put the unit in a smaller run (`[{text:'50', options:{fontSize:110}}, {text:' kat', options:{fontSize:40}}]`).
- **Pull quote:** 28–36 pt italic `F.head` (serif pairings), a large accent quotation mark (“ at 120 pt) as a
  shape-like element, attribution 13 pt uppercase with `charSpacing: 2`.
- **Mixed-emphasis titles:** one key word of a cover/statement title in the accent color via rich runs.
- **Slide numbers / footer:** 10–11 pt `F.body`, gray, bottom-right, on content slides only.
Never use more than the pairing's two families, never underline for emphasis, never all-caps body text.

## Type scale — use only these sizes
| Role | Size (pt) | Weight |
|---|---|---|
| Cover title | 48–60 | bold |
| Slide title | 28–34 | bold |
| Eyebrow label | 12–13 | bold, uppercase, charSpacing 3 |
| Section divider label | 40–54 | bold |
| Big number | 88–120 | bold |
| Card heading | 18–22 | bold |
| Lead sentence | 20–22 | regular |
| Body | 18–20 (dense cards 16, never below 14) | regular |
| Caption / source / label | 12–13 | regular |

- At most 3 sizes per text block (eyebrow, title and footer don't count). Fonts from the pairing only.
- Line spacing: body `lineSpacingMultiple: 1.15`; paragraphs `paraSpaceAfter: 6`.
- Left-align body text. Center only short, single-idea text (cover, statements, numbers, quotes).
- Body text must fill its column: if a 16 pt paragraph uses less than half of the box height, go up to
  18–20 pt or add a lead sentence — small text floating in a large empty area looks unfinished.

## Fit text by arithmetic, not hope
pptxgenjs does not shrink text in the rendered file (`fit:'shrink'` only works after PowerPoint re-opens
it), so **size every text box so its text fits**:
- Characters per line ≈ `(w_in × 72) / (0.52 × fontSize)` (use 0.56 for bold or uppercase).
- Lines = Σ over paragraphs of `ceil(paragraphChars / charsPerLine)`.
- Needed height (in) ≈ `lines × fontSize × lineSpacingMultiple × 1.2 / 72 + 0.15`.
- If needed height > available height: first cut words, then reduce by one step of the type scale
  (never below 14 pt for body), then widen the box. Never let text run past its box or card.
- Example: body 16 pt in a 5.9 in box → ~51 chars/line; 4 bullets of ~90 chars → 8 lines → ~2.6 in.

## Word budgets
- Title ≤ 60 characters (one line at 30 pt across 12.13 in ≈ 55–60 chars).
- Bullet ≤ 90 characters, ≤ 4 bullets per box.
- Card body ≤ 30 words. A content slide ≤ 60 words in total (speaker notes carry the rest).

## Text boxes
- Always set `valign` (`top` for body blocks, `middle` for titles and centered statements).
- Set `margin: [4, 6, 4, 6]` (pt) on text inside cards so text never touches card edges.
- Text placed on a card must lie fully inside the card: text box = card box inset by ≥ 0.15 in.
- Use `breakLine: true` runs or separate paragraphs, never manual "\n" padding to push text down.

## Turkish
- Keep all diacritics (ç ğ ı İ ö ş ü); uppercase Turkish correctly (i → İ, ı → I) — prefer sentence case
  to avoid it entirely. Turkish words are long: budget ~10 % more width than for English.

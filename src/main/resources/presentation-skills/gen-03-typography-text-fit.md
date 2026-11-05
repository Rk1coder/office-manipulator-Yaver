# Typography and text fit (the #1 cause of broken slides is text that does not fit)

## Type scale — use only these sizes
| Role | Size (pt) | Weight |
|---|---|---|
| Cover title | 48–56 | bold |
| Slide title | 28–32 | bold |
| Section divider label | 40–48 | bold |
| Big number | 72–110 | bold |
| Card heading | 18–20 | bold |
| Body | 16–18 (never below 14) | regular |
| Caption / source / label | 12–13 | regular |

- At most 3 sizes on a content slide. One font family for headings, one for body, all slides.
- Line spacing: body `lineSpacingMultiple: 1.15`; paragraphs `paraSpaceAfter: 6`.
- Left-align body text. Center only short, single-idea text (cover, statements, numbers, quotes).

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

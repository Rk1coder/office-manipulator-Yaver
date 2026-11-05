# Images

- Generated images (AVAILABLE GENERATED IMAGES / `process.env.IMG_<n>`) are square-ish PNGs. Always place
  them with `sizing: { type: 'cover', w, h }` (same w/h as the box) so they fill the box without distortion;
  never stretch with mismatched w:h.
- Give an image a real share of the slide: ≥ 40 % of the slide width in split layouts, the full slide in
  FULL_BLEED_IMAGE. Small thumbnail images look like clip art.
- Align the image box to the grid (see layout guide): flush with the body zone, same top as the text.
- Text never overlaps an image except in FULL_BLEED_IMAGE, and then only on an overlay box.
- Keep images rectangular and their treatment (size, position, framing) consistent across the deck.
- A slide whose planned image is missing uses a strong typographic or shape-based layout instead —
  never a gray placeholder box, never a broken image.
- Never put the deck's key facts only inside an image; images support the text, they don't replace it.

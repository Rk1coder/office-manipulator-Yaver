# Color and contrast

## Palette structure (decide once, use on every slide)
- **Dark** (backgrounds of cover/dividers/closing, primary text on light slides): a deep, topic-appropriate
  hue — e.g. forest "1F3A33", ink "1B1F3B", aubergine "2E1A47", petrol "0F3B4C". Not pure black.
- **Light** (content slide backgrounds): an off-white "F7F7F5" / "F5F7FA", or white.
- **Accent** (one): a saturated color for the single most important element per slide — key number,
  active step, highlighted bar. Used on ≤ 10 % of a slide's area.
- **Neutrals:** mid-gray "6B7280" for secondary text, light gray "E5E7EB" for dividers and card borders,
  a tinted card fill (accent at ~8–12 % strength, e.g. "EAF4F0").
- If a DESIGN DIRECTION or plan colorPalette is given, derive these roles from it instead.

## Contrast rules (must hold on every slide)
- Body text vs. its background: contrast ratio ≥ 4.5 : 1; large text (≥ 24 pt bold) ≥ 3 : 1.
  - Dark text "1F2937" or darker on light fills; white "FFFFFF" on dark fills.
  - Never mid-gray text on a colored card; never accent-colored body text; never text on a busy image
    without an overlay.
- Text over an image: put it on a solid or ≥ 70 % opacity box (`fill:{color:'000000', transparency:30}`)
  or on the image's calm area only.
- Chart series colors must be distinguishable from each other and from the background.

## Consistency
- Same background treatment for all content slides; dark treatment only for cover, dividers, big
  statements and the closing slide ("sandwich").
- The accent means "this matters" everywhere — don't use it for decoration.
- No gradients across text areas, no neon, no more than 2 non-neutral hues besides the accent.

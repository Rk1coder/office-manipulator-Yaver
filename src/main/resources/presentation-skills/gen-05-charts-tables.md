# Charts and tables

## When
- Use a native chart whenever the content compares quantities, shows change over time, or shows parts of
  a whole. A number in a sentence that compares things is a chart waiting to happen.
- Use a table only for genuinely tabular lookup data (≤ 6 rows × 5 columns on a slide).

## Chart choice
- Comparison across categories → horizontal bar (`barDir:'bar'`), sorted descending.
- Change over time → line (≤ 4 series) or column for ≤ 8 periods.
- Part of whole → doughnut with ≤ 5 slices (merge the rest into "Diğer"); never 3-D, never pie with 8 slices.
- Single headline number → BIG_NUMBER, not a chart.

## pptxgenjs chart recipe
```javascript
slide.addChart(pptx.ChartType.bar, [{ name: 'Seri', labels: ['A', 'B', 'C'], values: [42, 31, 18] }], {
  x: 0.6, y: 1.55, w: 8.3, h: 5.3, barDir: 'bar',
  chartColors: ['2F6F5E'],                 // accent for the highlighted series; neutrals for others
  showValue: true, dataLabelFontSize: 12, dataLabelColor: '1F2937',
  catAxisLabelFontSize: 13, catAxisLabelColor: '374151',
  valAxisHidden: true, valGridLine: { style: 'none' },
  showLegend: false,                        // legend only for 2+ series: legendPos 'b', legendFontSize 12
});
```
- Title the slide with the insight ("Kuzey bölgesi satışların yarısını taşıyor"), not "Satış Grafiği".
- Highlight the one bar/point the title talks about with the accent; keep the others neutral gray.
- Data must come from the source material or the plan. Label units (%, ₺, adet) on the axis or labels.

## Tables
```javascript
slide.addTable(rows, { x: 0.6, y: 1.55, w: 12.13, colW: [...], fontSize: 14, color: '1F2937',
  border: { type: 'solid', pt: 0.75, color: 'E5E7EB' }, rowH: 0.5, valign: 'middle' });
```
- Header row: bold, filled with the dark or accent tint, white or dark text by contrast rule.
- Right-align numbers, left-align text, zebra fill ("F5F7FA") on alternate rows for > 4 rows.
- Total row bold with a top border. Never shrink below 12 pt to squeeze rows in — split the table.

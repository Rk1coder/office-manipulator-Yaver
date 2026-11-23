package com.officemanipulator.validator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrandPaletteTest {
    private static final List<String> PALETTE = List.of("1F4E79", "C00000");

    @Test void paletteTintsAndNeutralsPassOtherHuesAreReported() {
        String script = """
                slide.background = { color: 'FFFFFF' };
                slide.addText('a', { color: "1F4E79", fill: { color: 'DCE6F2' } });   // primary + tint
                cell.font = { color: { argb: 'FFC00000' } };                           // argb red
                cell.fill = { fgColor: { argb: 'FF333333' } };                         // gray
                page.drawText('t', { color: '38334A' });                               // slate text
                slide.addText('g', { color: '6B7280' });                               // cool gray
                slide.addShape(r, { fill: { color: '#2E7D32' } });                     // green: off-brand
                page.drawText('x', { color: rgb(0.95, 0.6, 0.1) });                    // orange: off-brand
                page.drawRectangle({ color: rgb(r, g, b) });                           // variables: skipped
                const id = 'ABCDEF12';                                                 // 8 hex but not FF-argb
                """;
        assertEquals(List.of("2E7D32", "F2991A"), BrandPalette.offPalette(script, PALETTE));
    }

    @Test void noPaletteMeansNoCheck() {
        assertEquals(List.of(), BrandPalette.offPalette("'2E7D32'", List.of()));
    }

    @Test void aGrayBrandColorDoesNotAllowEveryRed() {
        assertFalse(BrandPalette.allowed("C00000", List.of("333333")));
    }
}

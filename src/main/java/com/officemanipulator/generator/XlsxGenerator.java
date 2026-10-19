package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateXlsxParams;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;

@Component
public class XlsxGenerator {
    private final NodeScriptExecutor executor;
    public XlsxGenerator(NodeScriptExecutor executor) { this.executor = executor; }

    public void generate(CreateXlsxParams params, Path outputPath) throws IOException, InterruptedException {
        executor.execute(params.getScript(), "gen_xlsx.js", outputPath);
        try (var input = Files.newInputStream(outputPath); var wb = new XSSFWorkbook(input)) {
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.setForceFormulaRecalculation(true);
            if (params.getCharts() != null) {
                int offset = 0;
                for (var spec : params.getCharts()) {
                    var sheet = wb.getSheet(spec.sheet());
                    if (sheet == null) throw new IOException("Chart worksheet not found: " + spec.sheet());
                    var categories = CellRangeAddress.valueOf(spec.categories());
                    var values = CellRangeAddress.valueOf(spec.values());
                    if (categories.getFirstColumn() != categories.getLastColumn() || values.getFirstColumn() != values.getLastColumn()
                            || categories.getNumberOfCells() != values.getNumberOfCells() || values.getNumberOfCells() > 10000)
                        throw new IOException("Chart ranges must be equal-length single columns (maximum 10000 cells)");
                    for (int row=values.getFirstRow(); row<=values.getLastRow(); row++) {
                        var cell = sheet.getRow(row) == null ? null : sheet.getRow(row).getCell(values.getFirstColumn());
                        if (cell == null || (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.NUMERIC
                                && !(cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA && cell.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.NUMERIC)))
                            throw new IOException("Chart values must be numeric: " + spec.values());
                    }
                    int row = sheet.getLastRowNum() + 3 + offset;
                    var drawing = sheet.createDrawingPatriarch();
                    var chart = drawing.createChart(drawing.createAnchor(0,0,0,0,0,row,8,row+16));
                    chart.setTitleText(spec.title()); chart.setTitleOverlay(false);
                    var x = chart.createCategoryAxis(AxisPosition.BOTTOM);
                    var y = chart.createValueAxis(AxisPosition.LEFT); y.setCrosses(AxisCrosses.AUTO_ZERO);
                    if (!"line".equals(spec.type()) && !"bar".equals(spec.type())) throw new IOException("Unsupported chart type");
                    var data = chart.createData("line".equals(spec.type()) ? ChartTypes.LINE : ChartTypes.BAR,x,y);
                    if (data instanceof XDDFBarChartData bars) bars.setBarDirection(BarDirection.COL);
                    var firstCategory = sheet.getRow(categories.getFirstRow()).getCell(categories.getFirstColumn());
                    boolean numericCategory = firstCategory != null && (firstCategory.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            || firstCategory.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA && firstCategory.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.NUMERIC);
                    XDDFDataSource<?> categoryData = numericCategory ? XDDFDataSourcesFactory.fromNumericCellRange(sheet,categories)
                            : XDDFDataSourcesFactory.fromStringCellRange(sheet,categories);
                    var series = data.addSeries(categoryData,XDDFDataSourcesFactory.fromNumericCellRange(sheet,values));
                    series.setTitle(spec.title(),null); chart.plot(data); offset += 18;
                }
            }
            for (var sheet : wb) {
                sheet.setFitToPage(true);
                sheet.getPrintSetup().setFitWidth((short)1);
                sheet.getPrintSetup().setFitHeight((short)0);
                sheet.getPrintSetup().setLandscape(true);
            }
            try (var output=Files.newOutputStream(outputPath)) { wb.write(output); }
        }
    }
}

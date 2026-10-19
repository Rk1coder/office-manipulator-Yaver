package com.officemanipulator.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.CreateXlsxParams;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class XlsxChartTest {
 @TempDir Path temp;
 @Test void requestedChartUsesEditableWorksheetCellsAndCachesFormulas() throws Exception {
  var params = new ObjectMapper().readValue("""
    {"script":"const ExcelJS=require('exceljs'); const w=new ExcelJS.Workbook(); const s=w.addWorksheet('Veriler'); s.addRows([['Hafta','TL'],[1,1200],[2,1400],['Toplam',{formula:'SUM(B2:B3)'}]]); w.xlsx.writeFile(process.env.OUTPUT_PATH);",
    "charts":[{"sheet":"Veriler","title":"Harcama","categories":"A2:A3","values":"B2:B3","type":"bar"}]}
    """, CreateXlsxParams.class);
  Path file=temp.resolve("chart.xlsx");
  new XlsxGenerator(new NodeScriptExecutor()).generate(params,file);
  try(var wb=new XSSFWorkbook(Files.newInputStream(file))) {
   var sheet=wb.getSheet("Veriler");
   assertNotNull(sheet.getDrawingPatriarch());
   var charts=sheet.getDrawingPatriarch().getCharts();
   assertEquals(1,charts.size());
   String xml=charts.getFirst().getCTChart().xmlText();
   assertTrue(xml.contains("$B$2:$B$3"));
   var categoryCache=charts.getFirst().getCTChart().getPlotArea().getBarChartArray(0).getSerArray(0).getCat().getNumRef().getNumCache();
   assertEquals("1.0",categoryCache.getPtArray(0).getV());
   assertEquals("2.0",categoryCache.getPtArray(1).getV());
   assertEquals(2600,sheet.getRow(3).getCell(1).getNumericCellValue());
  }
 }
}

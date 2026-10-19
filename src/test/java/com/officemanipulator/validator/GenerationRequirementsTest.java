package com.officemanipulator.validator;
import com.officemanipulator.domain.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class GenerationRequirementsTest {
 @TempDir Path temp;
 @Test void missingRequestedChartIsNotSuccessButNoChartRequestIsAllowed() throws Exception {
  Path file=temp.resolve("out.xlsx");try(var wb=new XSSFWorkbook();var out=Files.newOutputStream(file)){wb.createSheet("Data");wb.write(out);}
  Job job=new Job();job.setFormat(OutputFormat.XLSX);job.setRequest("Haftalık gider grafiği ekle");
  assertThrows(IllegalArgumentException.class,()->GenerationRequirements.validate(job,file));
  job.setRequest("Sadece veri tablosu, grafik kullanma");
  assertDoesNotThrow(()->GenerationRequirements.validate(job,file));
  job.setRequest("Create spreadsheet without charts");
  assertDoesNotThrow(()->GenerationRequirements.validate(job,file));
 }
 @Test void missingSlideIsRejected() throws Exception {
  Path file=temp.resolve("out.pptx");try(var deck=new XMLSlideShow();var out=Files.newOutputStream(file)){deck.createSlide();deck.write(out);}
  Job job=new Job();job.setFormat(OutputFormat.PPTX);job.setRequest("Tam 4 slaytlık sunum hazırla");
  assertThrows(IllegalArgumentException.class,()->GenerationRequirements.validate(job,file));
 }
}

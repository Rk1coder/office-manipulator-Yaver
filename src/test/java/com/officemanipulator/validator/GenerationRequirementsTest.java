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
 @Test void generatedImagesMustAllAppearInTheDeck() throws Exception {
  Path png=temp.resolve("img_1.png");javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",png.toFile());
  Path file=temp.resolve("images.pptx");
  Job job=new Job();job.setFormat(OutputFormat.PPTX);job.setRequest("Karınca sunumu");job.setImagePathsJson("{\"1\":\""+png+"\",\"4\":\""+png+"\"}");
  try(var deck=new XMLSlideShow();var out=Files.newOutputStream(file)){var data=deck.addPicture(Files.readAllBytes(png),org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);deck.createSlide().createPicture(data);deck.createSlide();deck.write(out);}
  var error=assertThrows(IllegalArgumentException.class,()->GenerationRequirements.validate(job,file));
  assertTrue(error.getMessage().contains("IMG_1, IMG_4"),error.getMessage());
  job.setImagePathsJson(null);
  assertDoesNotThrow(()->GenerationRequirements.validate(job,file));
 }
}

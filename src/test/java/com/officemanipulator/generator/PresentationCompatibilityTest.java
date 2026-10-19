package com.officemanipulator.generator;
import com.officemanipulator.claude.dto.CreatePresentationParams;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PresentationCompatibilityTest {
 @TempDir Path temp;
 @Test void commonlyGeneratedStaticEnumsProduceAnEditableShape() throws Exception {
  var params=new CreatePresentationParams();params.setScript("const P=require('pptxgenjs'); const p=new P();const s=p.addSlide();s.addShape(P.ShapeType.rect,{x:1,y:1,w:2,h:2,fill:{color:'00AA00'}});p.writeFile({fileName:process.env.OUTPUT_PATH});");
  Path file=temp.resolve("slide.pptx");new PresentationGenerator(new NodeScriptExecutor()).generate(params,file);
  try(var input=Files.newInputStream(file);var deck=new XMLSlideShow(input)){assertEquals(1,deck.getSlides().size());assertEquals(1,deck.getSlides().getFirst().getShapes().size());}
 }
}

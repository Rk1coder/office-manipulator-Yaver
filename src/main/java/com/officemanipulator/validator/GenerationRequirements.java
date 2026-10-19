package com.officemanipulator.validator;

import com.officemanipulator.domain.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import java.nio.file.*;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks objectively measurable requests before declaring generation successful. */
public final class GenerationRequirements {
    private GenerationRequirements() {}
    public static void validate(Job job, Path path) throws Exception {
        String request=job.getRequest()==null ? "" : job.getRequest().toLowerCase(Locale.forLanguageTag("tr"));
        boolean chart=request.matches("(?s).*(grafik|grafiğ|chart).*")
                && !request.matches("(?s).*\\b(?:no|without)\\s+(?:any\\s+)?charts?\\b.*")
                && !request.matches("(?s).*(grafik|grafiğ|chart)[^.!?\\n]{0,40}(kullanma|ekleme|istemiyorum|olmasın).*");
        if (job.getFormat().generationFormat()==OutputFormat.XLSX && chart) {
            try(var input=Files.newInputStream(path);var wb=new XSSFWorkbook(input)) {
                boolean found=false;
                for(var sheet:wb) {
                    var drawing=((org.apache.poi.xssf.usermodel.XSSFSheet)sheet).getDrawingPatriarch();
                    found |= drawing!=null && !drawing.getCharts().isEmpty();
                }
                if(!found) throw new IllegalArgumentException("Requested editable chart is missing. For XLSX return charts metadata with worksheet ranges; ExcelJS has no addChart API.");
            }
        }
        if(job.getFormat().generationFormat()==OutputFormat.PPTX) {
            try(var input=Files.newInputStream(path);var deck=new XMLSlideShow(input)) {
                var count=Pattern.compile("\\b(\\d{1,2})\\s*(?:slayt|slides?)(?:lık|lik)?").matcher(request);
                if(count.find() && deck.getSlides().size()!=Integer.parseInt(count.group(1)))
                    throw new IllegalArgumentException("Requested slide count is " + count.group(1) + "; produced " + deck.getSlides().size());
                if(chart && deck.getCharts().isEmpty()) throw new IllegalArgumentException("Requested editable numeric chart is missing; use pptxgenjs slide.addChart.");
                if(request.contains("her slaytta") && request.contains("konuşmacı not"))
                    for(var slide:deck.getSlides()) {
                        if(slide.getNotes()==null || java.util.Arrays.stream(slide.getNotes().getPlaceholders()).noneMatch(p->p.getText()!=null && p.getText().strip().length()>20))
                            throw new IllegalArgumentException("Speaker notes are missing from slide " + slide.getSlideNumber());
                    }
            }
        }
    }
}

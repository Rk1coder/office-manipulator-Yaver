package com.officemanipulator.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.OutputFormat;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class OfficeDocumentClient {
    public static final int MAX_BYTES=100*1024*1024;
    private final URI internal,external;
    private final ObjectMapper json;
    private final OfficeTokens tokens;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public OfficeDocumentClient(ObjectMapper json,OfficeTokens tokens,String internal,String external) {
        this.json=json;this.tokens=tokens;this.internal=URI.create(internal);this.external=URI.create(external);
    }
    private static int port(URI uri){return uri.getPort()!=-1?uri.getPort():"https".equals(uri.getScheme())?443:80;}
    private static boolean sameOrigin(URI a,URI b) {
        return a.getHost()!=null && a.getHost().equalsIgnoreCase(b.getHost()) && a.getScheme().equals(b.getScheme()) && port(a)==port(b);
    }
    URI safeDownloadUri(String url) {
        try {
            URI uri=URI.create(url);
            String path=uri.getRawPath();
            if(!(sameOrigin(uri,internal)||sameOrigin(uri,external)) || uri.getUserInfo()!=null || uri.getFragment()!=null
                || path==null || !path.startsWith("/cache/files/") || path.contains("%") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException();
            return URI.create(internal.toString()+path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
        } catch(RuntimeException e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Editörden gelen dosya adresi geçersiz");}
    }
    public byte[] download(String url,OutputFormat format) {
        try {
            var response=http.send(HttpRequest.newBuilder(safeDownloadUri(url)).timeout(Duration.ofSeconds(30)).GET().build(),limitedBody());
            // Bounded subscriber also handles responses without Content-Length.
            if(response.statusCode()!=200 || response.body().length>MAX_BYTES) throw new IllegalStateException();
            validate(response.body(),format);return response.body();
        } catch(ResponseStatusException e) {throw e;}
        catch(Exception e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Düzenlenen belge indirilemedi veya geçerli "+format+" değil");}
    }
    private static HttpResponse.BodyHandler<byte[]> limitedBody() {
        return info -> new HttpResponse.BodySubscriber<>() {
            final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
            final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            java.util.concurrent.Flow.Subscription subscription;
            public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
            public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
                for(var buffer:buffers){
                    if((long)bytes.size()+buffer.remaining()>MAX_BYTES){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Document size limit"));return;}
                    byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
                }
                subscription.request(1);
            }
            public void onError(Throwable failure){result.completeExceptionally(failure);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        };
    }
    private URI safeConvertedUri(String url) {
        try {
            URI uri=URI.create(url);
            String path=uri.getRawPath();
            if(!sameOrigin(uri,internal) || uri.getUserInfo()!=null || uri.getFragment()!=null
                || path==null || path.contains("%") || path.contains("..") || path.contains("\\")) throw new IllegalArgumentException();
            return URI.create(internal.toString()+path+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery()));
        } catch(RuntimeException e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Dönüştürülen belgenin adresi geçersiz");}
    }
    /** Converts a document to PDF via ONLYOFFICE's ConvertService, polling until the async job finishes. */
    public byte[] convertToPdf(String sourceUrl,String fileType,String key) {
        return convertTo(sourceUrl,fileType,"pdf",key);
    }
    /** Converts a document to any format ONLYOFFICE's ConvertService supports (e.g. docx-&gt;odt, xlsx-&gt;ods, pptx-&gt;odp, docx-&gt;doc). */
    public byte[] convertTo(String sourceUrl,String fileType,String outputType,String key) {
        try {
            Map<String,Object> body=new LinkedHashMap<>();
            body.put("async",false);body.put("filetype",fileType);body.put("key",key);
            body.put("outputtype",outputType);body.put("title","convert."+fileType);body.put("url",sourceUrl);
            body.put("token",tokens.sign(body));
            byte[] payload=json.writeValueAsBytes(body);
            for(int attempt=0;attempt<30;attempt++) {
                var response=http.send(HttpRequest.newBuilder(internal.resolve("/ConvertService.ashx")).timeout(Duration.ofSeconds(25))
                    .header("Content-Type","application/json").header("Accept","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200) throw new IllegalStateException("ConvertService HTTP "+response.statusCode());
                var result=json.readTree(response.body());
                int error=result.path("error").asInt(0);
                if(error!=0) throw new IllegalStateException("ConvertService error "+error);
                if(result.path("endConvert").asBoolean(false)) {
                    String fileUrl=result.path("fileUrl").asText(null);
                    if(fileUrl==null) throw new IllegalStateException("ConvertService returned no fileUrl");
                    var fileResponse=http.send(HttpRequest.newBuilder(safeConvertedUri(fileUrl)).timeout(Duration.ofSeconds(30)).GET().build(),limitedBody());
                    if(fileResponse.statusCode()!=200 || fileResponse.body().length==0 || fileResponse.body().length>MAX_BYTES) throw new IllegalStateException();
                    return fileResponse.body();
                }
                Thread.sleep(500);
            }
            throw new IllegalStateException("ConvertService timed out");
        } catch(ResponseStatusException e) {throw e;}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Önizleme oluşturma iptal edildi");}
        catch(Exception e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Belge önizleme için dönüştürülemedi");}
    }
    public int forceSave(String key,UUID requestId) {
        try {
            Map<String,Object> body=new LinkedHashMap<>(Map.of("c","forcesave","key",key,"userdata",requestId.toString()));body.put("token",tokens.sign(body));
            var response=http.send(HttpRequest.newBuilder(internal.resolve("/coauthoring/CommandService.ashx")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException();
            int error=json.readTree(response.body()).path("error").asInt(-1);
            if(error!=0 && error!=4) throw new IllegalStateException();
            return error;
        } catch(Exception e) {throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Editöre kaydetme isteği iletilemedi");}
    }
    public static void validate(byte[] bytes,OutputFormat format) {
        if(bytes.length==0 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Document size limit");
        try {
            switch(format) {
                case DOCX -> {
                    try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
                        if(document.getDocument().getBody()==null) throw new IllegalArgumentException("Missing document body");
                        rejectMacros(document.getPackage());
                    }
                }
                case PPTX -> {
                    try(var ppt=new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(bytes))) {
                        rejectMacros(ppt.getPackage());
                    }
                }
                case XLSX -> {
                    try(var workbook=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
                        rejectMacros(workbook.getPackage());
                    }
                }
                case CSV -> {
                    if(com.officemanipulator.preview.CsvSupport.rows(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).isEmpty())
                        throw new IllegalArgumentException("Empty CSV");
                }
                case PDF -> {
                    try(var doc=org.apache.pdfbox.Loader.loadPDF(bytes)) {
                        if(doc.getNumberOfPages()==0) throw new IllegalArgumentException("PDF has no pages");
                    }
                }
                case ODT -> com.officemanipulator.validator.DocumentSignatures.requireNonBlankOdt(bytes);
                case ODS -> com.officemanipulator.validator.DocumentSignatures.requireNonBlankOds(bytes);
                case ODP -> com.officemanipulator.validator.DocumentSignatures.requireNonBlankOdp(bytes);
                case DOC -> com.officemanipulator.validator.DocumentSignatures.requireNonBlankDoc(bytes);
                case TXT,MD -> com.officemanipulator.validator.DocumentSignatures.requireNonBlankText(bytes);
                case JSON -> com.officemanipulator.validator.DocumentSignatures.requireValidJson(bytes,new com.fasterxml.jackson.databind.ObjectMapper());
                case XML -> com.officemanipulator.validator.DocumentSignatures.requireWellFormedXml(bytes);
                case ODB -> com.officemanipulator.validator.DocumentSignatures.requireOdfZipSignature(bytes,"application/vnd.oasis.opendocument.database");
            }
        } catch(IllegalArgumentException e) {throw e;}
        catch(Exception e) {throw new IllegalArgumentException("Invalid "+format,e);}
    }
    private static void rejectMacros(org.apache.poi.openxml4j.opc.OPCPackage pkg) throws org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        for(var part:pkg.getParts())
            if(part.getPartName().getName().toLowerCase(Locale.ROOT).contains("vbaproject")) throw new IllegalArgumentException("Macros not supported");
    }
}

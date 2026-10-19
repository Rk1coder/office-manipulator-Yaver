package com.officemanipulator.generator;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Runs trusted synthetic input through the same mandatory executor before declaring native startup ready. */
@Component
@ConditionalOnProperty(name="app.sandbox.preflight",havingValue="true")
public class SandboxStartupCheck implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(SandboxStartupCheck.class);
    private final NodeScriptExecutor executor;
    public SandboxStartupCheck(NodeScriptExecutor executor){this.executor=executor;}

    @Override public void run(ApplicationArguments args) throws Exception {
        Path directory=Files.createTempDirectory("office-startup-check-");
        try {
            Path output=directory.resolve("check.docx");
            executor.execute("""
                const fs=require('fs');
                const {Document,Packer,Paragraph}=require('docx');
                const doc=new Document({sections:[{children:[new Paragraph('Sandbox Türkçe: ğüşıöç ĞÜŞİÖÇ')]}]});
                Packer.toBuffer(doc).then(bytes=>fs.writeFileSync(process.env.OUTPUT_PATH,bytes)).catch(()=>process.exitCode=1);
                ""","startup_check.js",output);
            try(var document=new XWPFDocument(Files.newInputStream(output))) {
                boolean valid=document.getParagraphs().stream().anyMatch(p->p.getText().contains("ğüşıöç ĞÜŞİÖÇ"));
                if(!valid) throw new IllegalStateException("Sandbox DOCX validation failed");
            }
            log.info("Document generation sandbox ready");
        } catch(Exception failure) {
            throw new IllegalStateException("Document generation is unavailable: Node, prlimit, or the Unicode fonts are missing or misconfigured.",failure);
        } finally {
            try(var paths=Files.walk(directory)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}

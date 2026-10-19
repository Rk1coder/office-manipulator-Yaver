package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateCsvParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Best-effort, experimental generator for LibreOffice Base (.odb) files: a single table (from
 * the same columns/rows shape as {@link CreateCsvParams}) backed by a real embedded HSQLDB
 * database, wrapped in a minimal OpenDocument-Database zip container. ONLYOFFICE cannot open
 * this format at all (no editor, no AI assistant, no preview) — it is generate-and-download only.
 * Real-world compatibility with LibreOffice Base has not been verified against an actual install
 * and should be tested manually.
 */
@Component
public class OdbGenerator {

    public void generate(CreateCsvParams params, Path outputPath) throws IOException {
        Path workDir = Files.createTempDirectory("odb-" + UUID.randomUUID());
        Path dbPath = workDir.resolve("database");
        try {
            buildDatabase(dbPath, params);
            Files.createDirectories(outputPath.getParent());
            try (OutputStream fileOut = Files.newOutputStream(outputPath);
                 ZipOutputStream zip = new ZipOutputStream(fileOut)) {
                writeStoredEntry(zip, "mimetype", "application/vnd.oasis.opendocument.database".getBytes(StandardCharsets.UTF_8));
                writeEntry(zip, "META-INF/manifest.xml", manifestXml().getBytes(StandardCharsets.UTF_8));
                writeEntry(zip, "content.xml", minimalContentXml().getBytes(StandardCharsets.UTF_8));
                writeEntry(zip, "database/script", Files.exists(dbPath.resolveSibling("database.script")) ? Files.readAllBytes(dbPath.resolveSibling("database.script")) : new byte[0]);
                writeEntry(zip, "database/properties", Files.exists(dbPath.resolveSibling("database.properties")) ? Files.readAllBytes(dbPath.resolveSibling("database.properties")) : new byte[0]);
            }
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void buildDatabase(Path dbPath, CreateCsvParams params) throws IOException {
        String url = "jdbc:hsqldb:file:" + dbPath + ";shutdown=true";
        try (Connection conn = DriverManager.getConnection(url, "SA", "")) {
            List<String> columns = params.getColumns();
            String ddl = "CREATE TABLE DATA_TABLE (" +
                    String.join(",", columns.stream().map(c -> quoteIdentifier(c) + " VARCHAR(4000)").toList()) + ")";
            try (Statement st = conn.createStatement()) {
                st.execute(ddl);
            }
            String insertSql = "INSERT INTO DATA_TABLE VALUES (" +
                    String.join(",", columns.stream().map(c -> "?").toList()) + ")";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (var row : params.getRows()) {
                    for (int i = 0; i < columns.size(); i++) ps.setString(i + 1, i < row.size() ? row.get(i) : null);
                    ps.executeUpdate();
                }
            }
            try (Statement st = conn.createStatement()) {
                st.execute("SHUTDOWN");
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("Failed to build embedded HSQLDB database for ODB", e);
        }
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "").trim() + "\"";
    }

    private static String manifestXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                    <manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="application/vnd.oasis.opendocument.database"/>
                    <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                    <manifest:file-entry manifest:full-path="database/script" manifest:media-type=""/>
                    <manifest:file-entry manifest:full-path="database/properties" manifest:media-type=""/>
                </manifest:manifest>
                """;
    }

    private static String minimalContentXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:db="urn:oasis:names:tc:opendocument:xmlns:database:1.0" office:version="1.2">
                    <office:body>
                        <office:database>
                            <db:data-source db:driver-name="org.hsqldb.jdbcDriver" db:url="sdbc:embedded:hsqldb"/>
                        </office:database>
                    </office:body>
                </office:document-content>
                """;
    }

    private static void writeStoredEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}

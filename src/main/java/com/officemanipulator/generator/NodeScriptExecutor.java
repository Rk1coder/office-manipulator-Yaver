package com.officemanipulator.generator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes model-generated Node scripts under restrictions that don't require Linux user
 * namespaces (unavailable on some container hosts, e.g. Docker Desktop's VM): Node's own
 * permission model confines filesystem access (and denies child-process/worker/addon spawning
 * by default), {@code prlimit} caps CPU/memory/file-size/fd usage, and the process environment
 * is cleared and replaced with an explicit allowlist — the script never sees the Anthropic API
 * key, database credentials, or any other job's files. Network access is not sandboxed (no user
 * namespace means no network namespace); this is an accepted residual risk since the script can
 * only read its own job's already-submitted content, nothing secret.
 */
@Component
public class NodeScriptExecutor {
    private static final Logger log = LoggerFactory.getLogger(NodeScriptExecutor.class);
    private static final long TIMEOUT_SECONDS = 60;
    private static final int LOG_LIMIT = 64 * 1024;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    public void execute(String script, String scriptFileName, Path outputPath)
            throws IOException, InterruptedException {
        execute(script, scriptFileName, outputPath, Map.of());
    }

    public void execute(String script, String scriptFileName, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        if (scriptFileName == null || !scriptFileName.matches("[A-Za-z0-9_-]+\\.js")) {
            throw new IOException("Invalid generator script name");
        }
        Path destination = outputPath.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path jobDirectory = destination.getParent().toRealPath();
        Path workspace = Files.createTempDirectory("office-node-");
        Process process = null;
        try {
            Files.writeString(workspace.resolve(scriptFileName), script);
            // Named with the real extension (not a bare "result"): some npm libraries (e.g.
            // pptxgenjs) silently append their own extension to an extension-less filename.
            Path result = workspace.resolve(destination.getFileName().toString());
            Map<String, String> environment = stageImages(extraEnv, jobDirectory, workspace);
            ProcessBuilder builder = sandboxProcess(workspace, scriptFileName, result, environment);
            process = builder.start();
            // Consume both pipes immediately, but retain only a bounded prefix of each.
            BoundedOutput stdout = new BoundedOutput(process.getInputStream());
            BoundedOutput stderr = new BoundedOutput(process.getErrorStream());
            Thread outReader = Thread.ofVirtual().start(stdout);
            Thread errReader = Thread.ofVirtual().start(stderr);
            process.getOutputStream().close();
            try {
                // Never wait for EOF before starting the wall-clock deadline.
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException(scriptFileName + " timed out after " + TIMEOUT_SECONDS + "s");
                }
                outReader.join(1000);
                errReader.join(1000);
                if (process.exitValue() != 0) {
                    throw new IOException(scriptFileName + " failed (exit "
                            + process.exitValue() + "). Check Node 24 and prlimit. "
                            + stdout.text() + stderr.text());
                }
                if (!Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS) || Files.size(result) == 0) {
                    throw new IOException(scriptFileName + " produced no output: " + stdout.text() + stderr.text());
                }
                if (Files.size(result) > MAX_FILE_BYTES) {
                    throw new IOException("Generated document exceeds the 64 MiB limit");
                }
                Files.copy(result, destination, StandardCopyOption.REPLACE_EXISTING);
                log.info("{} → {} bytes", scriptFileName, Files.size(destination));
            } finally {
                terminateTree(process);
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        } finally {
            if (process != null && process.isAlive()) terminateTree(process);
            // walk() does not follow symlinks: child-controlled names cannot escape cleanup.
            try (var paths = Files.walk(workspace)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private Map<String, String> stageImages(Map<String, String> extraEnv, Path jobDirectory, Path workspace)
            throws IOException {
        Map<String, String> environment = new HashMap<>();
        for (var entry : extraEnv.entrySet()) {
            if (!entry.getKey().matches("IMG_[0-9]+")) {
                throw new IOException("Unsupported generator environment key: " + entry.getKey());
            }
            Path image = Path.of(entry.getValue()).toRealPath();
            if (!image.startsWith(jobDirectory) || !Files.isRegularFile(image) || Files.size(image) > MAX_FILE_BYTES) {
                throw new IOException("Generator image must be a regular file belonging to this job");
            }
            String name = entry.getKey() + ".png";
            Files.copy(image, workspace.resolve(name));
            environment.put(entry.getKey(), workspace.resolve(name).toString());
        }
        return environment;
    }

    private ProcessBuilder sandboxProcess(Path workspace, String scriptFileName, Path result, Map<String, String> imageEnv)
            throws IOException {
        Path node = executable("node.binary", "node");
        Path prlimit = executable("node.prlimit.binary", "prlimit");
        Path modules = Path.of(System.getProperty("node.modules.path",
                Path.of(System.getProperty("user.dir"), "node_modules").toString())).toRealPath();
        Path regularFont = font("TURKISH_FONT_REGULAR", "DejaVuSans.ttf");
        Path boldFont = font("TURKISH_FONT_BOLD", "DejaVuSans-Bold.ttf");
        List<String> command = new ArrayList<>(List.of(prlimit.toString(),
                "--as=2147483648", "--cpu=60", "--fsize=" + MAX_FILE_BYTES, "--nofile=128", "--core=0", "--",
                node.toString(), "--max-old-space-size=256", "--disable-wasm-trap-handler",
                "--permission",
                "--allow-fs-read=" + workspace, "--allow-fs-read=" + modules,
                "--allow-fs-read=" + regularFont, "--allow-fs-read=" + boldFont,
                "--allow-fs-write=" + workspace,
                workspace.resolve(scriptFileName).toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("HOME", workspace.toString());
        env.put("TMPDIR", workspace.toString());
        env.put("LANG", "C.UTF-8");
        env.put("OUTPUT_PATH", result.toString());
        env.put("NODE_PATH", modules.toString());
        env.put("TURKISH_FONT_REGULAR", regularFont.toString());
        env.put("TURKISH_FONT_BOLD", boldFont.toString());
        env.putAll(imageEnv);
        builder.directory(workspace.toFile());
        return builder;
    }

    private Path executable(String property, String name) throws IOException {
        String configured = System.getProperty(property);
        if (configured != null) {
            Path path = Path.of(configured).toRealPath();
            if (Files.isExecutable(path)) return path;
        } else {
            for (String directory : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
                Path path = Path.of(directory, name);
                if (Files.isRegularFile(path) && Files.isExecutable(path)) return path.toRealPath();
            }
        }
        throw new IOException("Mandatory sandbox dependency unavailable: " + name + "; no unsafe fallback is permitted");
    }

    private Path font(String environmentKey, String filename) throws IOException {
        String configured = System.getenv(environmentKey);
        if (configured != null && !configured.isBlank()) return Path.of(configured).toRealPath();
        for (String directory : List.of("/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu")) {
            Path candidate = Path.of(directory, filename);
            if (Files.isRegularFile(candidate)) return candidate.toRealPath();
        }
        throw new IOException("Missing Unicode font: install DejaVu Sans or configure " + environmentKey);
    }

    private void terminateTree(Process process) {
        // Killing the namespace's PID 1 also kills reparented descendants. Java traversal is defense in depth.
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroyForcibly();
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BoundedOutput implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
        private boolean truncated;

        private BoundedOutput(InputStream stream) { this.stream = stream; }

        @Override public void run() {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (this) {
                        int keep = Math.min(count, LOG_LIMIT - retained.size());
                        retained.write(buffer, 0, keep);
                        if (keep < count) truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // Expected when the timeout/interruption path closes the process pipes.
            }
        }

        private synchronized String text() {
            return retained.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated]\n" : "");
        }
    }
}

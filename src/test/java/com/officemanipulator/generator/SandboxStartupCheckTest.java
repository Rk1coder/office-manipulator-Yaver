package com.officemanipulator.generator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxStartupCheckTest {
    @Test void startupFailsWhenMandatorySandboxCannotRun() throws Exception {
        NodeScriptExecutor executor=mock(NodeScriptExecutor.class);
        doThrow(new IOException("bwrap namespace denied")).when(executor).execute(anyString(),anyString(),any(Path.class));
        assertThrows(IllegalStateException.class,()->new SandboxStartupCheck(executor).run(new DefaultApplicationArguments()));
    }
    @Test void realHostSandboxProducesValidatedDocxAtStartup() throws Exception {
        assertDoesNotThrow(()->new SandboxStartupCheck(new NodeScriptExecutor()).run(new DefaultApplicationArguments()));
    }
}

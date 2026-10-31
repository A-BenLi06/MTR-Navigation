package io.github.teamcreating.mtrnav;

import io.github.teamcreating.mtrnav.client.OverlaySupport;
import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import static org.junit.jupiter.api.Assertions.*;

class OverlayAbsenceTest {
    @Test void baseClientLoadsAndLegacyBridgeFallsBackWithNoOverlayOnClasspath() throws Exception {
        try (var isolated = new URLClassLoader(new java.net.URL[]{OverlaySupport.class.getProtectionDomain().getCodeSource().getLocation()}, ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> isolated.loadClass("io.github.teamcreating.mtrmap.api.MtrMapApi"));
            var support = isolated.loadClass("io.github.teamcreating.mtrnav.client.OverlaySupport");
            support.getMethod("initialize", Executor.class, BiConsumer.class).invoke(null, (Executor) Runnable::run, (BiConsumer<String, String>) (dim, id) -> fail());
            assertEquals("", support.getMethod("status").invoke(null));
            assertNull(support.getMethod("catalog", String.class).invoke(null, "minecraft/overworld"));
            var legacy = isolated.loadClass("io.github.teamcreating.mtrnav.client.OverlayBridge");
            assertNull(legacy.getMethod("catalog", String.class).invoke(null, "minecraft/overworld"));
            support.getMethod("reset").invoke(null);
        }
    }
}

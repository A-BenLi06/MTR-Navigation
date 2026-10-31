package io.github.teamcreating.mtrnav.client;

import io.github.teamcreating.mtrnav.api.JourneySelection;
import io.github.teamcreating.mtrnav.api.NavigationApi;
import io.github.teamcreating.mtrnav.core.JourneyTracker;
import io.github.teamcreating.mtrnav.core.Model;
import io.github.teamcreating.mtrnav.core.NativeTimetable;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Optional class-loading boundary: no Overlay API types escape into the base client. */
public final class OverlaySupport {
    public interface Adapter {
        void tick(String dimension, JourneySelection selection, JourneyTracker.State tracking, Model.Catalog catalog);
        void reset();
        String status();
        Model.Catalog catalog(String dimension);
        Map<String, List<NativeTimetable.PlatformIdentity>> platforms(String dimension);
    }
    private static Adapter adapter;
    private static boolean checked;
    private OverlaySupport() {}

    public static void initialize(Executor clientThread, BiConsumer<String, String> navigate) {
        if (checked) return;
        checked = true;
        try {
            Class.forName("io.github.teamcreating.mtrmap.api.MtrMapApi");
            adapter = (Adapter) Class.forName("io.github.teamcreating.mtrnav.client.OverlayNavigation")
                    .getConstructor(Executor.class, BiConsumer.class).newInstance(clientThread, navigate);
        } catch (ClassNotFoundException ignored) {
            // Overlay absent or older than 1.5.3; the existing read-only fallback still works.
        } catch (ReflectiveOperationException | LinkageError e) {
            System.getLogger("MTR Navigation").log(System.Logger.Level.WARNING, "Overlay integration unavailable", e);
        }
    }
    public static void tick(String dimension, JourneyTracker.State tracking, Model.Catalog catalog) {
        try { if (adapter != null) adapter.tick(dimension, NavigationApi.current().orElse(null), tracking, catalog); }
        catch (RuntimeException | LinkageError e) { disable(e); }
    }
    public static void reset() {
        try { if (adapter != null) adapter.reset(); }
        catch (RuntimeException | LinkageError e) { disable(e); }
    }
    public static String status() { return adapter == null ? "" : adapter.status(); }
    public static Model.Catalog catalog(String dimension) {
        try { return adapter == null ? null : adapter.catalog(dimension); }
        catch (RuntimeException | LinkageError e) { disable(e); return null; }
    }
    public static Map<String, List<NativeTimetable.PlatformIdentity>> platforms(String dimension) {
        try { return adapter == null ? Map.of() : adapter.platforms(dimension); }
        catch (RuntimeException | LinkageError e) { disable(e); return Map.of(); }
    }
    private static void disable(Throwable error) {
        var previous = adapter; adapter = null;
        try { if (previous != null) previous.reset(); } catch (RuntimeException | LinkageError ignored) { }
        System.getLogger("MTR Navigation").log(System.Logger.Level.WARNING, "Overlay integration disabled", error);
    }
}

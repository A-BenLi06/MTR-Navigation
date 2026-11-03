package io.github.teamcreating.mtrnav.client;

import io.github.teamcreating.mtrmap.api.MtrMapApi;
import io.github.teamcreating.mtrmap.api.MtrMapApi.*;
import io.github.teamcreating.mtrnav.api.JourneySelection;
import io.github.teamcreating.mtrnav.core.JourneyTracker;
import io.github.teamcreating.mtrnav.core.Model;
import io.github.teamcreating.mtrnav.core.NativeTimetable;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Loaded only with Overlay's public API. No renderer, network or internal Overlay dependencies. */
public final class OverlayNavigation implements OverlaySupport.Adapter {
    private static final String OWNER = "mtrnav:journey";
    private final Executor clientThread;
    private final BiConsumer<String, String> navigate;
    private Client client;
    private Registration registration;
    private ViewSession view;
    private Key desired;
    private long generation;
    private String status = "";
    private Catalog projectedSource;
    private Model.Catalog projected;
    private record Key(WorldRef world, long revision, JourneySelection selection, int completed) {}

    public OverlayNavigation(Executor clientThread, BiConsumer<String, String> navigate) {
        this.clientThread = clientThread; this.navigate = navigate;
    }
    // Explicit client injection keeps asynchronous and lifecycle behavior testable without Minecraft.
    public OverlayNavigation(Client client, Executor clientThread, BiConsumer<String, String> navigate) {
        this(clientThread, navigate); this.client = client;
    }
    private boolean attach() {
        if (client == null) client = MtrMapApi.findClient().orElse(null);
        if (client == null) return false;
        if (registration == null) registration = client.registerNavigation(OWNER, "MTR Navigation", target -> {
            var current = client.world(target.world().dimensionId()).orElse(null);
            var catalog = client.catalog(target.world());
            if (target.world().equals(current) && catalog.status() == Status.READY
                    && catalog.stations().stream().anyMatch(s -> s.id().equals(target.stationId())))
                navigate.accept(toNavigationDimension(target.world().dimensionId()), target.stationId());
        });
        return true;
    }
    public static String toOverlayDimension(String dimension) { return dimension.replaceFirst("/", ":"); }
    public static String toNavigationDimension(String dimension) { return dimension.replaceFirst(":", "/"); }

    @Override public void tick(String dimension, JourneySelection selection, JourneyTracker.State tracking, Model.Catalog navigation) {
        try {
            if (!attach()) return;
            if (tracking == null) {
                if (desired != null || view != null || !status.isEmpty()) reset();
                return;
            }
            if (selection == null || !tracking.journey().id().equals(selection.journeyId())) { fail("mtrnav.map_unavailable"); return; }
            if (selection.version() != 1 || selection.segments().isEmpty() || !selection.dimension().equals(dimension)
                    || navigation == null || !navigation.dimension().equals(dimension) || selection.topologyRevision() != navigation.revision()) {
                fail("mtrnav.map_unavailable"); return;
            }
            var world = client.world(toOverlayDimension(dimension)).orElse(null);
            if (world == null) { fail("mtrnav.map_waiting"); return; }
            var catalog = client.catalog(world);
            if (catalog.status() != Status.READY) { fail("mtrnav.map_waiting"); return; }
            int completed = tracking.phase() == JourneyTracker.Phase.COMPLETE ? selection.segments().size() : tracking.legIndex();
            var key = new Key(world, catalog.revision(), selection, completed);
            if (key.equals(desired)) return;
            reset(); desired = key;
            var sections = sections(catalog, navigation, selection);
            if (sections.isEmpty()) { status = "mtrnav.map_unavailable"; return; }
            status = "mtrnav.map_loading";
            long token = generation;
            var resolutions = sections.stream().map(s -> client.resolve(world, catalog.revision(), List.of(s)).toCompletableFuture()).toList();
            CompletableFuture.allOf(resolutions.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> clientThread.execute(() -> {
                if (token != generation || !key.equals(desired)) return;
                if (error != null) { status = "mtrnav.map_unavailable"; return; }
                var latest = client.catalog(world);
                if (latest.status() != Status.READY || latest.revision() != key.revision()
                        || !client.world(world.dimensionId()).filter(world::equals).isPresent()) { reset(); return; }
                var remaining = new ArrayList<SelectedSpan>(); var finished = new ArrayList<SelectedSpan>();
                for (int i = 0; i < resolutions.size(); i++) {
                    var result = resolutions.get(i).join();
                    if (result.status() != Status.READY || result.catalogRevision() != key.revision() || result.spans().isEmpty()) {
                        status = result.status() == Status.AMBIGUOUS ? "mtrnav.map_ambiguous" : "mtrnav.map_unavailable"; return;
                    }
                    (i < key.completed() ? finished : remaining).addAll(result.spans());
                }
                try {
                    var request = navigationStyle(catalog, sections, remaining, finished);
                    view = client.openView(OWNER, "MTR Navigation", Purpose.FOCUS, 0);
                    view.replace(request).whenComplete((result, failure) -> clientThread.execute(() -> {
                        if (token != generation) return;
                        status = failure == null && result == Status.READY ? "mtrnav.map_ready" : "mtrnav.map_unavailable";
                        if (failure != null || result != Status.READY) closeView();
                    }));
                } catch (RuntimeException e) { closeView(); status = "mtrnav.map_unavailable"; }
            }));
        } catch (RuntimeException | LinkageError e) {
            fail("mtrnav.map_unavailable");
        }
    }

    /** Quiet context, bold native line colours, and only stops on the exact ride occurrences. */
    static ViewRequest navigationStyle(Catalog catalog, List<RouteSection> sections,
                                       List<SelectedSpan> remaining, List<SelectedSpan> finished) {
        var routes = new HashMap<String, Route>();
        var context = new LinkedHashMap<String, Style>();
        for (var route : catalog.routes()) {
            routes.put(route.id(), route);
            context.put(route.id(), new Style(Optional.of(0x98a2af), 1, .75, Pattern.SOLID));
        }
        var stations = new LinkedHashSet<String>();
        for (var section : sections) {
            var route = routes.get(section.routeId());
            for (int i = section.fromStop(); i <= section.toStop(); i++) {
                var id = route.stops().get(i).stationId();
                if (!id.isBlank()) stations.add(id);
            }
        }
        var all = new ArrayList<SelectedSpan>(finished); all.addAll(remaining);
        var styles = new ArrayList<SpanStyle>();
        for (var span : finished) styles.add(new SpanStyle(span,
                new Style(Optional.of(routes.get(span.routeId()).color()), .35, 1.6, Pattern.SOLID)));
        // Local colour overrides restore native colours above the neutral route context.
        // Remaining wins on repeated/shared intervals, as with ViewRequest.navigation.
        for (var span : remaining) styles.add(new SpanStyle(span,
                new Style(Optional.of(routes.get(span.routeId()).color()), 1, 3, Pattern.SOLID)));
        return new ViewRequest(catalog.world(), catalog.revision(), all, Set.of(), Unmatched.DIM,
                context, true, stations, styles, .3);
    }

    /** Validate the whole ordered stop sequence, including repeated occurrences, before resolving geometry. */
    static List<RouteSection> sections(Catalog catalog, Model.Catalog navigation, JourneySelection selection) {
        var routes = new HashMap<String, Route>(); catalog.routes().forEach(r -> routes.put(r.id(), r));
        var nativeRoutes = new HashMap<String, Model.Route>(); navigation.routes().forEach(r -> nativeRoutes.put(r.id(), r));
        var sections = new ArrayList<RouteSection>();
        for (var segment : selection.segments()) {
            var route = routes.get(segment.routeId()); var original = nativeRoutes.get(segment.routeId());
            if (route == null || original == null || segment.fromStopIndex() < 0 || segment.fromStopIndex() >= segment.toStopIndex()
                    || segment.toStopIndex() >= route.stops().size() || original.platforms().size() != route.stops().size()) return List.of();
            for (int i = 0; i < route.stops().size(); i++) {
                var stop = route.stops().get(i);
                if (stop.ordinal() != i || !stop.platformId().equals(original.platforms().get(i))) return List.of();
            }
            if (!route.stops().get(segment.fromStopIndex()).platformId().equals(segment.fromPlatformId())
                    || !route.stops().get(segment.toStopIndex()).platformId().equals(segment.toPlatformId())) return List.of();
            sections.add(new RouteSection(route.id(), segment.fromStopIndex(), segment.toStopIndex(), Optional.empty()));
        }
        return List.copyOf(sections);
    }

    private void closeView() { if (view != null) { view.close(); view = null; } }
    private void fail(String message) { if (desired != null || view != null) reset(); status = message; }
    @Override public void reset() { generation++; closeView(); desired = null; status = ""; projectedSource = null; projected = null; }
    @Override public String status() { return status; }
    private Catalog readyCatalog(String dimension) {
        if (!attach()) return null;
        var world = client.world(toOverlayDimension(dimension)).orElse(null);
        if (world == null) return null;
        var value = client.catalog(world);
        return value.status() == Status.READY ? value : null;
    }
    @Override public Model.Catalog catalog(String dimension) {
        var source = readyCatalog(dimension);
        if (source == null) return null;
        if (source == projectedSource) return projected;
        projectedSource = source;
        projected = new Model.Catalog(dimension, source.revision(), source.stations().stream().map(s -> new Model.Station(
                s.id(), s.name(), (long)s.position().x(), (long)s.position().y(), (long)s.position().z(), List.of())).toList(),
                source.platforms().stream().map(p -> new Model.Platform(p.id(), p.stationId(), p.name(),
                        (long)p.position().x(), (long)p.position().y(), (long)p.position().z())).toList(),
                source.routes().stream().map(r -> new Model.Route(r.id(), r.name(), r.color(), r.stops().stream().map(Stop::platformId).toList(), false)).toList());
        return projected;
    }
    @Override public Map<String, List<NativeTimetable.PlatformIdentity>> platforms(String dimension) {
        var source = readyCatalog(dimension);
        if (source == null) return Map.of();
        var result = new HashMap<String, List<NativeTimetable.PlatformIdentity>>();
        source.routes().forEach(r -> result.put(r.id(), r.stops().stream()
                .map(s -> new NativeTimetable.PlatformIdentity(s.platformId(), s.stationId())).toList()));
        return Map.copyOf(result);
    }
}

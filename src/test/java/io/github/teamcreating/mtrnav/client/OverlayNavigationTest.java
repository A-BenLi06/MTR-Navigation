package io.github.teamcreating.mtrnav.client;

import io.github.teamcreating.mtrmap.api.MtrMapApi.*;
import io.github.teamcreating.mtrnav.api.JourneySelection;
import io.github.teamcreating.mtrnav.core.JourneyTracker;
import io.github.teamcreating.mtrnav.core.Model;
import org.junit.jupiter.api.*;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Integration tests use the resolver AND view registry shipped in the SHA256-pinned Overlay 1.5.3 JAR. */
class OverlayNavigationTest {
    private static URLClassLoader release;
    private static Class<?> resolver, registryType;
    private FakeClient client;
    private OverlayNavigation bridge;
    private Model.Catalog navigation;
    private Model.Journey journey;
    private JourneySelection selection;

    @BeforeAll static void loadRelease() throws Exception {
        var jar = Path.of(System.getProperty("mtrnav.root"), ".cache/downloads/CRTools-MTR-Map-Overlay-1.5.3-MC1.21.1-MTR4.1.0-beta.2-neoforge.jar");
        assertTrue(jar.toFile().isFile(), "Run python tools/download_overlay_api.py first");
        release = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, OverlayNavigation.class.getClassLoader());
        resolver = release.loadClass("io.github.teamcreating.mtrmap.extension.SectionResolver");
        registryType = release.loadClass("io.github.teamcreating.mtrmap.extension.ViewRegistry");
    }
    @AfterAll static void closeRelease() throws Exception { if (release != null) release.close(); }
    @BeforeEach void fixture() throws Exception {
        var world = new WorldRef(UUID.randomUUID(), "minecraft:overworld");
        var circle = route("a1", List.of("1", "2", "1", "3"), List.of("early", "return", "chosen"));
        var transfer = route("a2", List.of("4", "5"), List.of("second"));
        var stations = List.of(new Station("10", "Origin", new Point(0, 64, 0), List.of("1")),
                new Station("20", "Transfer", new Point(100, 64, 0), List.of("3", "4")),
                new Station("30", "Destination", new Point(200, 64, 0), List.of("5")));
        client = new FakeClient(new Catalog(world, 42, 9, Status.READY, stations, List.of(), List.of(circle, transfer)));
        bridge = new OverlayNavigation(client, Runnable::run, (dim, station) -> {});
        navigation = new Model.Catalog("minecraft/overworld", 123, List.of(), List.of(), client.catalog.routes().stream()
                .map(r -> new Model.Route(r.id(), r.name(), r.color(), r.stops().stream().map(Stop::platformId).toList(), true)).toList());
        var run = new Model.RunKey("ff", 0, 1, 0);
        journey = new Model.Journey("journey", navigation.dimension(), navigation.revision(), "10", "30", 0, 1000, 1,
                List.of(new Model.RideLeg(run, "", "a1", "10", "20", "1", "3", 2, 3, "", 0, 500, false, List.of()),
                        new Model.RideLeg(run, "", "a2", "20", "30", "4", "5", 0, 1, "", 600, 1000, false, List.of())));
        selection = JourneySelection.from(journey);
    }
    private static Route route(String id, List<String> platforms, List<String> rails) {
        var stops = new ArrayList<Stop>(); var legs = new ArrayList<Leg>();
        for (int i = 0; i < platforms.size(); i++) stops.add(new Stop(i, platforms.get(i), "10", ""));
        for (int i = 0; i < rails.size(); i++) legs.add(new Leg(i, i + 1, List.of(new RailSpan(rails.get(i), .2, .8))));
        return new Route(id, id, "", 0xffffff, "normal", stops, List.of(new PathVariant("native", legs)));
    }
    private void tick(int completed, JourneyTracker.Phase phase) {
        bridge.tick(navigation.dimension(), selection, new JourneyTracker.State(journey, completed, phase, "", false), navigation);
    }
    @Test void highlightsExactOccurrencesAcrossTwoRidesWithIndependentRevisions() throws Exception {
        tick(0, JourneyTracker.Phase.WAITING);
        var view = client.request(); assertNotNull(view);
        assertEquals(List.of("chosen", "second"), view.spans().stream().map(s -> s.rail().railId()).toList());
        assertEquals(List.of("a1", "a2"), view.spans().stream().map(SelectedSpan::routeId).toList());
        assertEquals(.2, view.spans().getFirst().rail().from()); assertEquals(.8, view.spans().getFirst().rail().to());
        assertEquals(42, view.catalogRevision()); assertEquals(Unmatched.DIM, view.unmatched());
        assertEquals(.15, view.unmatchedOpacity()); assertTrue(view.showTrains());
        assertEquals("mtrnav.map_ready", bridge.status());
    }
    @Test void progressDimsCompletedRideAndPreviousRestoresItWithoutPerTickRequests() throws Exception {
        tick(0, JourneyTracker.Phase.WAITING); tick(0, JourneyTracker.Phase.RIDING);
        assertEquals(2, client.resolutions);
        tick(1, JourneyTracker.Phase.TRANSFER);
        var styles = client.request().spanStyles();
        assertEquals(.35, styles.getFirst().style().opacity()); assertEquals(1, styles.getLast().style().opacity());
        tick(0, JourneyTracker.Phase.WAITING);
        assertTrue(client.request().spanStyles().stream().allMatch(s -> s.style().opacity() == 1));
        tick(2, JourneyTracker.Phase.COMPLETE);
        assertTrue(client.request().spanStyles().stream().allMatch(s -> s.style().opacity() == .35));
    }
    @Test void walkingDoesNotInventRailsAndDimsOnlyEarlierRides() throws Exception {
        var walk = new Model.WalkLeg("walk", "20", "20", "3", "4", 30, 500, 1, "local");
        journey = new Model.Journey(journey.id(), journey.dimension(), journey.revision(), "10", "30", 0, 1000, 1, journey.legs(), List.of(walk), 1);
        selection = JourneySelection.from(journey);
        bridge.tick(navigation.dimension(), selection, new JourneyTracker.State(journey, 1, JourneyTracker.Phase.WALKING, "", false, 1), navigation);
        assertEquals(2, client.request().spans().size());
        assertEquals(.35, client.request().spanStyles().getFirst().style().opacity());
        assertEquals(1, client.request().spanStyles().getLast().style().opacity());
    }
    @Test void cancelClosesOnlyNavigationViewAndDoesNotUnregisterProvider() throws Exception {
        var other = client.openView("another:planner", "Other", Purpose.FOCUS, -1);
        tick(0, JourneyTracker.Phase.WAITING);
        var old = client.request(); other.replace(old);
        bridge.tick(navigation.dimension(), null, null, navigation);
        assertEquals(1, client.activeCount()); assertEquals(1, client.registrations);
        tick(0, JourneyTracker.Phase.WAITING); assertEquals(1, client.registrations);
    }
    @Test void ambiguousNativePathsClearPreviousJourneyInsteadOfChoosingOne() throws Exception {
        tick(0, JourneyTracker.Phase.WAITING);
        var first = client.catalog.routes().getFirst();
        replaceCatalog(43, List.of(new Route(first.id(), first.name(), "", first.color(), first.type(), first.stops(),
                List.of(first.paths().getFirst(), new PathVariant("alternate", first.paths().getFirst().legs()))), client.catalog.routes().getLast()));
        tick(0, JourneyTracker.Phase.WAITING);
        assertNull(client.request()); assertEquals("mtrnav.map_ambiguous", bridge.status());
    }
    @Test void staleAndUnsupportedCatalogsDoNotResolveAndRecoverWhenReady() throws Exception {
        var original = client.catalog;
        client.catalog = new Catalog(original.world(), 42, 9, Status.UNSUPPORTED, original.stations(), List.of(), original.routes());
        tick(0, JourneyTracker.Phase.WAITING); assertEquals(0, client.resolutions); assertNull(client.request());
        client.catalog = original; tick(0, JourneyTracker.Phase.WAITING); assertNotNull(client.request());
        client.catalog = new Catalog(original.world(), 42, 9, Status.STALE, original.stations(), List.of(), original.routes());
        tick(0, JourneyTracker.Phase.WAITING); assertNull(client.request());
    }
    @Test void lateResolutionAfterCancelOrDisconnectCannotRestoreOldView() throws Exception {
        client.delay = true; tick(0, JourneyTracker.Phase.WAITING);
        bridge.reset(); client.complete(); assertNull(client.request());
        tick(0, JourneyTracker.Phase.WAITING); client.connected = false; client.complete();
        assertNull(client.request());
    }
    @Test void oldAsyncResultCannotReplaceNewProgress() throws Exception {
        client.delay = true; tick(0, JourneyTracker.Phase.WAITING);
        tick(1, JourneyTracker.Phase.TRANSFER); client.complete();
        assertEquals(.35, client.request().spanStyles().getFirst().style().opacity());
    }
    @Test void reconnectWithNewEpochReopensSessionAndKeepsSingleRegistration() throws Exception {
        tick(0, JourneyTracker.Phase.WAITING); bridge.reset();
        var c = client.catalog;
        client.catalog = new Catalog(new WorldRef(UUID.randomUUID(), c.world().dimensionId()), c.revision(), c.baseStaticRevision(), c.status(), c.stations(), c.platforms(), c.routes());
        tick(0, JourneyTracker.Phase.WAITING);
        assertEquals(client.catalog.world(), client.request().world()); assertEquals(1, client.registrations);
    }
    @Test void invalidOccurrenceOrPlaceholderNeverInvokesResolver() throws Exception {
        selection = new JourneySelection(1, journey.dimension(), journey.revision(), journey.id(), List.of(new JourneySelection.Segment("a1", "map:a1:2", "3", 2, 3, "")));
        tick(0, JourneyTracker.Phase.WAITING); assertEquals(0, client.resolutions);
        assertEquals("mtrnav.map_unavailable", bridge.status());
    }
    @Test void intermediateStopMismatchRejectsEvenIfEndpointsStillMatch() {
        var original = client.catalog.routes().getFirst(); var stops = new ArrayList<>(original.stops());
        stops.set(1, new Stop(1, "different", "10", ""));
        replaceCatalog(43, List.of(new Route(original.id(), original.name(), "", 0, original.type(), stops, original.paths()), client.catalog.routes().getLast()));
        tick(0, JourneyTracker.Phase.WAITING); assertEquals(0, client.resolutions);
    }
    @Test void topologyChangeAndDimensionChangeReleasePreviousFocus() throws Exception {
        tick(0, JourneyTracker.Phase.WAITING);
        navigation = new Model.Catalog(navigation.dimension(), 124, List.of(), List.of(), navigation.routes());
        tick(0, JourneyTracker.Phase.WAITING); assertNull(client.request());
        bridge.tick("minecraft/the_nether", selection, new JourneyTracker.State(journey, 0, JourneyTracker.Phase.WAITING, "", false), navigation);
        assertNull(client.request());
    }
    @Test void unresolvedNativeSelectionExplainsMissingHighlightWithoutRequestingGeometry() throws Exception {
        bridge.tick(navigation.dimension(), null, new JourneyTracker.State(journey, 0, JourneyTracker.Phase.WAITING, "", false), navigation);
        assertEquals("mtrnav.map_unavailable", bridge.status()); assertEquals(0, client.resolutions); assertNull(client.request());
    }
    @Test void navigateOnlyAcceptsCurrentEpochAndKnownStation() {
        var targets = new ArrayList<String>();
        bridge = new OverlayNavigation(client, Runnable::run, (dim, station) -> targets.add(dim + "#" + station));
        tick(0, JourneyTracker.Phase.WAITING);
        client.handler.accept(new NavigationTarget(client.catalog.world(), "30"));
        client.handler.accept(new NavigationTarget(new WorldRef(UUID.randomUUID(), "minecraft:overworld"), "30"));
        client.handler.accept(new NavigationTarget(client.catalog.world(), "unknown"));
        assertEquals(List.of("minecraft/overworld#30"), targets);
    }
    @Test void catalogEnrichesNativePlatformIdentitiesBeyondNearbyClientData() {
        var catalog = bridge.catalog("minecraft/overworld"); assertNotNull(catalog);
        assertEquals("30", catalog.stations().getLast().id()); assertEquals(200, catalog.stations().getLast().x());
        assertEquals(List.of("1", "2", "1", "3"), bridge.platforms("minecraft/overworld").get("a1").stream().map(p -> p.platformId()).toList());
    }
    @Test void dimensionConversionPreservesNamespacedNestedPaths() {
        assertEquals("example:world/deep", OverlayNavigation.toOverlayDimension("example/world/deep"));
        assertEquals("example/world/deep", OverlayNavigation.toNavigationDimension("example:world/deep"));
    }
    private void replaceCatalog(long revision, List<Route> routes) {
        var c = client.catalog; client.catalog = new Catalog(c.world(), revision, c.baseStaticRevision(), c.status(), c.stations(), c.platforms(), routes);
    }
    private static final class FakeClient implements Client {
        Catalog catalog; boolean connected = true, delay; int resolutions, registrations;
        Consumer<NavigationTarget> handler;
        final Object registry;
        final List<Runnable> pending = new ArrayList<>();
        FakeClient(Catalog catalog) throws Exception { this.catalog = catalog; registry = registryType.getConstructor().newInstance(); }
        public Optional<WorldRef> world(String dimension) { return connected && catalog.world().dimensionId().equals(dimension) ? Optional.of(catalog.world()) : Optional.empty(); }
        public Catalog catalog(WorldRef world) { return catalog; }
        public List<Track> tracks(WorldRef world, Set<String> ids) { throw new AssertionError("Navigation must not fetch or reconstruct rails"); }
        public CompletionStage<Resolution> resolve(WorldRef world, long revision, List<RouteSection> sections) {
            resolutions++;
            try {
                var result = (Resolution) resolver.getMethod("resolve", Catalog.class, List.class).invoke(null, catalog, sections);
                if (!delay) return CompletableFuture.completedFuture(result);
                var future = new CompletableFuture<Resolution>(); pending.add(() -> future.complete(result)); return future;
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        void complete() { var callbacks = List.copyOf(pending); pending.clear(); callbacks.forEach(Runnable::run); }
        public ViewSession openView(String owner, String name, Purpose purpose, int priority) {
            try { return (ViewSession) registryType.getMethod("open", String.class, String.class, Purpose.class, int.class, java.util.function.Function.class)
                    .invoke(registry, owner, name, purpose, priority, (java.util.function.Function<WorldRef, Catalog>) this::catalog); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        public BoardSubscription watchStation(WorldRef world, String id, Consumer<Board> listener) { throw new AssertionError("Navigation does not need station subscriptions"); }
        public Registration registerNavigation(String owner, String name, Consumer<NavigationTarget> handler) { registrations++; this.handler = handler; return () -> {}; }
        List<?> active() throws Exception { return (List<?>) registryType.getMethod("active", String.class).invoke(registry, catalog.world().dimensionId()); }
        int activeCount() throws Exception { return active().size(); }
        ViewRequest request() throws Exception {
            var active = active(); if (active.isEmpty()) return null;
            var last = active.getLast(); return (ViewRequest) last.getClass().getMethod("request").invoke(last);
        }
    }
}

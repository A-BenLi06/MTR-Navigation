package io.github.teamcreating.mtrnav;

import io.github.teamcreating.mtrnav.core.*;
import io.github.teamcreating.mtrnav.api.*;
import io.github.teamcreating.mtrnav.network.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static io.github.teamcreating.mtrnav.core.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class NavigationCoreTest {
    private static final long NOW = 1_000_000;
    private static final String DIM = "minecraft/overworld";
    private final RoutePlanner planner = new RoutePlanner();
    private static Station s(String id) { return new Station(id, id, 0, 64, 0, List.of()); }
    private static Platform p(String id, String station) { return new Platform(id, station, id, 0, 64, 0); }
    private static StopEvent e(String siding, int trip, String route, String platform, int index, int seconds) {
        return new StopEvent(new RunKey(siding, trip, 0, 0), siding + ":vehicle", route, platform, index, "destination", NOW + seconds * 1000L,
                NOW + seconds * 1000L + 5000, 0, true);
    }
    private static Snapshot snapshot(List<Station> stations, List<Platform> platforms, StopEvent... events) {
        return new Snapshot(new Catalog(DIM, 42, stations, platforms, List.of()), NOW, List.of(events), Map.of());
    }
    private static Snapshot example() {
        return snapshot(List.of(s("滨海北"), s("南苑"), s("甘地")), List.of(p("a", "滨海北"), p("b", "南苑"), p("c", "南苑"), p("d", "甘地")),
                e("s1", 0, "c2", "a", 0, 10), e("s1", 0, "c2", "b", 1, 100), e("s2", 0, "line2", "c", 0, 150), e("s2", 0, "line2", "d", 1, 250));
    }
    @Test void exactTwoLegMapContractAndCancel() throws Exception {
        var journey = planner.find(example(), "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).getFirst();
        assertEquals(1, journey.transfers()); assertEquals(2, journey.legs().size());
        var events = new ArrayList<NavigationApi.Change>();
        try (var registration = NavigationApi.listen((change, selection) -> events.add(change))) {
            NavigationApi.select(JourneySelection.from(journey));
            var segments = NavigationApi.current().orElseThrow().segments();
            assertEquals(new JourneySelection.Segment("c2", "a", "b", 0, 1, "destination"), segments.getFirst());
            assertEquals(new JourneySelection.Segment("line2", "c", "d", 0, 1, "destination"), segments.getLast());
            assertThrows(UnsupportedOperationException.class, () -> segments.clear());
            NavigationApi.cancel(); assertTrue(NavigationApi.current().isEmpty());
        }
        assertEquals(List.of(NavigationApi.Change.SELECTED, NavigationApi.Change.CANCELLED), events);
    }
    @Test void cannotCatchTransferBeforeBuffer() {
        var source = example(); var events = new ArrayList<>(source.events());
        events.set(2, e("s2", 0, "line2", "c", 0, 110));
        assertTrue(planner.find(new Snapshot(source.catalog(), NOW, events, Map.of()), "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).isEmpty());
    }
    @Test void respectsDirectionAndNoDepartures() {
        assertTrue(planner.find(example(), "甘地", "滨海北", Sort.FASTEST, Settings.defaults()).isEmpty());
        assertTrue(planner.find(new Snapshot(example().catalog(), NOW, List.of(), Map.of()), "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).isEmpty());
    }
    @Test void exactPlatformOccurrenceOnCircularRoute() {
        var source = snapshot(List.of(s("A"), s("B"), s("C")), List.of(p("a", "A"), p("b", "B"), p("c", "C")),
                e("s", 0, "circle", "a", 0, -50), e("s", 0, "circle", "b", 1, -20), e("s", 0, "circle", "a", 2, 10), e("s", 0, "circle", "c", 3, 40));
        var journey = planner.find(source, "A", "C", Sort.FASTEST, Settings.defaults()).getFirst();
        assertEquals(2, journey.legs().getFirst().fromStopIndex()); assertEquals(3, journey.legs().getFirst().toStopIndex());
    }
    @Test void sameNamedStationsNeverMerge() {
        var source = snapshot(List.of(new Station("A", "same", 0, 64, 0, List.of()), new Station("B", "same", 0, 64, 0, List.of()), s("C")),
                List.of(p("a", "A"), p("b", "B"), p("c", "C")), e("s", 0, "r", "b", 0, 10), e("s", 0, "r", "c", 1, 100));
        assertTrue(planner.find(source, "A", "C", Sort.FASTEST, Settings.defaults()).isEmpty());
    }
    @Test void onlyExplicitStationConnectionAllowsWalking() {
        var stations = List.of(new Station("A", "A", 0, 64, 0, List.of("B")), s("B"), s("C"));
        var source = snapshot(stations, List.of(p("b", "B"), p("c", "C")), e("s", 0, "r", "b", 0, 50), e("s", 0, "r", "c", 1, 100));
        assertFalse(planner.find(source, "A", "C", Sort.FASTEST, Settings.defaults()).isEmpty());
    }
    @Test void throughServiceDoesNotRequireChangingTrains() {
        var source = snapshot(List.of(s("A"), s("B"), s("C")), List.of(p("a", "A"), p("b", "B"), p("c", "C")),
                e("s", 0, "r1", "a", 0, 10), e("s", 0, "r1", "b", 1, 50), e("s", 1, "r2", "b", 0, 50), e("s", 1, "r2", "c", 1, 100));
        var result = planner.find(source, "A", "C", Sort.FASTEST, new Settings(6, 0, 30)).getFirst();
        assertEquals(0, result.transfers()); assertEquals(2, result.legs().size());
    }
    @Test void fastestAndFewestTransfersChooseDifferently() {
        var source = example(); var events = new ArrayList<>(source.events());
        events.add(e("direct", 0, "r3", "a", 0, 20)); events.add(e("direct", 0, "r3", "d", 1, 400));
        var full = new Snapshot(source.catalog(), NOW, events, Map.of());
        assertEquals(1, planner.find(full, "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).getFirst().transfers());
        assertEquals(0, planner.find(full, "滨海北", "甘地", Sort.FEWEST_TRANSFERS, Settings.defaults()).getFirst().transfers());
    }
    @Test void horizonAndTransferLimitAreApplied() {
        assertTrue(planner.find(example(), "滨海北", "甘地", Sort.FASTEST, new Settings(6, 0, 30)).isEmpty());
        var source = example(); var events = source.events().stream().map(e -> new StopEvent(e.run(), e.vehicleId(), e.routeId(), e.platformId(), e.stopIndex(),
                e.destination(), e.arrival() + 7 * 3_600_000L, e.departure() + 7 * 3_600_000L, e.deviation(), e.realtime())).toList();
        assertTrue(planner.find(new Snapshot(source.catalog(), NOW, events, Map.of()), "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).isEmpty());
    }
    @Test void monotonicClockHandlesClientWallClockDifferenceAndExpiry() {
        var clock = new ServerClock(); assertTrue(clock.stale(0));
        clock.sample(1_000_000, 7_000_000_000L);
        assertEquals(1_002_000, clock.now(9_000_000_000L));
        assertFalse(clock.stale(11_999_999_999L)); assertTrue(clock.stale(12_000_000_000L));
        clock.clear(); assertTrue(clock.stale(13_000_000_000L));
    }
    @Test void trackerCannotConfuseDifferentSidingOrLoop() {
        var journey = planner.find(example(), "滨海北", "甘地", Sort.FASTEST, Settings.defaults()).getFirst();
        var leg = journey.legs().getFirst(); var player = UUID.randomUUID(); var tracker = new JourneyTracker(journey);
        var wrong = new Riding(leg.vehicleId(), leg.routeId(), leg.toPlatformId(), leg.toStopIndex(), false, new RunKey("other", 0, 0, 0));
        var state = tracker.update(new Snapshot(example().catalog(), NOW, example().events(), Map.of(player, wrong)), player);
        assertEquals("wrong_train", state.notice()); assertEquals(JourneyTracker.Phase.WAITING, state.phase());
        var correct = new Riding(leg.vehicleId(), leg.routeId(), leg.toPlatformId(), leg.toStopIndex(), true, leg.run());
        state = tracker.update(new Snapshot(example().catalog(), NOW, example().events(), Map.of(player, correct)), player);
        assertEquals("alight_now", state.notice());
        state = tracker.update(example(), player); assertEquals(1, state.legIndex());
        tracker.correct("previous"); assertEquals(0, tracker.state(false).legIndex());
    }
    @Test void wireRoundtripAndOutOfOrderChunks() throws Exception {
        var journeys = planner.find(example(), "滨海北", "甘地", Sort.FASTEST, Settings.defaults());
        var response = new Messages.Response("query", DIM, 12, 42, NOW, example().catalog(), journeys, example().events(), null, "");
        byte[] bytes = WireCodec.encode(response);
        assertEquals(response, WireCodec.decode(bytes));
        var assembly = new WireCodec.Assembly(); int half = bytes.length / 2;
        assertNull(assembly.accept(1, 1, 2, Arrays.copyOfRange(bytes, half, bytes.length), 10));
        assertArrayEquals(bytes, assembly.accept(1, 0, 2, Arrays.copyOfRange(bytes, 0, half), 20));
        assertThrows(java.io.IOException.class, () -> assembly.accept(2, 0, 9999, bytes, 30));
        assertThrows(java.io.IOException.class, () -> WireCodec.decode(new byte[]{1, 2, 3}));
    }
    @Test void nativeSuccessorRouteAtSharedTerminalStillRecognizesAlighting() {
        var source=example();var journey=planner.find(source,"滨海北","甘地",Sort.FASTEST,Settings.defaults()).getFirst();
        var leg=journey.legs().getFirst();var player=UUID.randomUUID();var tracker=new JourneyTracker(journey);
        var onboard=new Riding(leg.vehicleId(),leg.routeId(),leg.fromPlatformId(),leg.fromStopIndex(),true,leg.run());
        tracker.update(new Snapshot(source.catalog(),leg.departure(),source.events(),Map.of(player,onboard)),player);
        var successor=new Riding(leg.vehicleId(),"successor",leg.toPlatformId(),0,true,new RunKey(leg.run().sidingId(),1,leg.run().departureIndex(),leg.run().departureOffset()));
        var state=tracker.update(new Snapshot(source.catalog(),leg.arrival(),source.events(),Map.of(player,successor)),player);
        assertEquals("alight_now",state.notice());assertTrue(state.reliable());
        state=tracker.update(new Snapshot(source.catalog(),leg.arrival()+1000,source.events(),Map.of()),player);
        assertEquals(1,state.legIndex());
    }
    @Test void actualMatchingNextTrainCompletesTransferWalkWithoutManualCorrection() {
        var source=example();var original=planner.find(source,"滨海北","甘地",Sort.FASTEST,Settings.defaults()).getFirst();
        var walk=new WalkLeg("native-transfer","南苑","南苑","b","c",5,NOW+100000,1,"mtr");
        var journey=new Journey(original.id(),DIM,original.revision(),"滨海北","甘地",NOW,original.arrival(),1,original.legs(),List.of(walk),0);
        var player=UUID.randomUUID();var tracker=new JourneyTracker(journey);
        // Arrange the preceding completed ride; the tested transition itself uses only native passenger data.
        tracker.correct("next");assertEquals(JourneyTracker.Phase.WALKING,tracker.state(false).phase());
        var leg=journey.legs().getLast();
        var wrong=new Riding(leg.vehicleId(),leg.routeId(),leg.fromPlatformId(),0,true,new RunKey("wrong",0,0,0));
        assertEquals(JourneyTracker.Phase.WALKING,tracker.update(new Snapshot(source.catalog(),leg.departure(),source.events(),Map.of(player,wrong)),player).phase());
        var right=new Riding(leg.vehicleId(),leg.routeId(),leg.fromPlatformId(),0,true,leg.run());
        var state=tracker.update(new Snapshot(source.catalog(),leg.departure(),source.events(),Map.of(player,right)),player);
        assertEquals(JourneyTracker.Phase.RIDING,state.phase());assertEquals(1,state.legIndex());assertTrue(state.reliable());
    }
    @Test void terminalApproachRetainsBoardedNativeVehicleButDoesNotTrustAnotherVehicleOrExpiredRun() {
        var source=example();var journey=planner.find(source,"滨海北","甘地",Sort.FASTEST,Settings.defaults()).getFirst();
        var leg=journey.legs().getFirst();var player=UUID.randomUUID();var tracker=new JourneyTracker(journey);
        var onboard=new Riding(leg.vehicleId(),leg.routeId(),leg.fromPlatformId(),leg.fromStopIndex(),true,leg.run());
        tracker.update(new Snapshot(source.catalog(),leg.departure(),source.events(),Map.of(player,onboard)),player);
        var approach=new Riding(leg.vehicleId(),"","",-1,false,null);
        var state=tracker.update(new Snapshot(source.catalog(),leg.arrival()-1000,source.events(),Map.of(player,approach)),player);
        assertTrue(state.reliable());assertEquals("on_route",state.notice());
        var successor=new Riding(leg.vehicleId(),"successor","other-next-stop",0,false,new RunKey(leg.run().sidingId(),0,leg.run().departureIndex(),leg.run().departureOffset()+1));
        assertTrue(tracker.update(new Snapshot(source.catalog(),leg.arrival(),source.events(),Map.of(player,successor)),player).reliable());
        var other=new Riding("other-vehicle","","",-1,false,null);
        assertFalse(tracker.update(new Snapshot(source.catalog(),leg.arrival(),source.events(),Map.of(player,other)),player).reliable());
        assertFalse(tracker.update(new Snapshot(source.catalog(),leg.arrival()+30000,source.events(),Map.of(player,approach)),player).reliable());
    }
}

package io.github.teamcreating.mtrnav.server;

import io.github.teamcreating.mtrnav.core.Model.RunKey;
import io.github.teamcreating.mtrnav.core.Model.StopEvent;
import org.junit.jupiter.api.*;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeTerminalStopsTest {
    Simulator sim; Siding siding; Platform a,b,c; Route route;
    @BeforeEach void setup() throws Exception {
        sim=new Simulator("minecraft/overworld",new String[]{"minecraft/overworld"},Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")),"terminal-native-"),false);
        a=new Platform(new Position(0,64,0),new Position(60,64,0),TransportMode.TRAIN,sim);
        b=new Platform(new Position(100,64,0),new Position(160,64,0),TransportMode.TRAIN,sim);
        c=new Platform(new Position(200,64,0),new Position(260,64,0),TransportMode.TRAIN,sim);
        siding=new Siding(new Position(300,64,0),new Position(400,64,0),100,TransportMode.TRAIN,sim);
        route=route(List.of(a,b));
    }
    @AfterEach void stop(){sim.stop();}
    Route route(List<Platform> platforms){var r=new Route(TransportMode.TRAIN,sim);for(var p:platforms){var stop=new RoutePlatformData(p.getId());stop.platform=p;r.getRoutePlatforms().add(stop);}return r;}
    @SuppressWarnings("unchecked") void trips(Trip... values) throws Exception {
        var field=Siding.class.getDeclaredField("trips");field.setAccessible(true);((List<Trip>)field.get(siding)).addAll(List.of(values));
    }
    StopEvent anchor(Trip trip) {return new StopEvent(new RunKey(Long.toHexString(siding.getId()),trip.tripIndexInBlock,0,17),"native-vehicle",Long.toHexString(route.getId()),Long.toHexString(a.getId()),0,"B",1_005_000,1_015_000,5000,true);}
    @Test void suppressedRepeatingTerminalUsesNativeScheduleAndDoesNotDoubleDeviation() throws Exception {
        var trip=new Trip(route,0,0,siding);trip.addStopTime(0,10000,a.getId(),0,"B");trip.addStopTime(60000,70000,b.getId(),1,"B");trips(trip);
        var events=new ArrayList<>(List.of(anchor(trip)));new NativeTerminalStops().append(siding,events,0);
        assertEquals(2,events.size());var terminal=events.getLast();
        assertEquals(1_065_000,terminal.arrival());assertEquals(1_075_000,terminal.departure());
        assertEquals(events.getFirst().run(),terminal.run());assertEquals(Long.toHexString(b.getId()),terminal.platformId());assertTrue(terminal.realtime());
        new NativeTerminalStops().append(siding,events,0);assertEquals(2,events.size());
    }
    @Test void mergedSuccessorFirstStopCompletesPriorRouteWithoutChangingItsRun() throws Exception {
        var first=new Trip(route,0,0,siding);first.addStopTime(0,10000,a.getId(),0,"B");
        var next=new Trip(route(List.of(b,c)),1,1,siding);next.addStopTime(60000,70000,b.getId(),0,"C");next.addStopTime(120000,130000,c.getId(),1,"C");trips(first,next);
        var events=new ArrayList<>(List.of(anchor(first)));new NativeTerminalStops().append(siding,events,0);
        assertEquals(2,events.size());assertEquals(1,events.getLast().stopIndex());assertEquals(0,events.getLast().run().tripIndex());
        assertEquals(Long.toHexString(route.getId()),events.getLast().routeId());assertEquals(1_065_000,events.getLast().arrival());
    }
    @Test void missingNativeTerminalOrNonMatchingSuccessorDoesNotInventTimes() throws Exception {
        var first=new Trip(route,0,0,siding);first.addStopTime(0,10000,a.getId(),0,"B");
        var next=new Trip(route(List.of(c,a)),1,1,siding);next.addStopTime(60000,70000,c.getId(),0,"A");trips(first,next);
        var events=new ArrayList<>(List.of(anchor(first)));new NativeTerminalStops().append(siding,events,0);assertEquals(1,events.size());
    }
    @Test void mismatchedNativeStopSequenceIsRejected() throws Exception {
        var trip=new Trip(route,0,0,siding);trip.addStopTime(0,10000,c.getId(),0,"B");trip.addStopTime(60000,70000,b.getId(),1,"B");trips(trip);
        var events=new ArrayList<>(List.of(anchor(trip)));new NativeTerminalStops().append(siding,events,0);assertEquals(1,events.size());
    }
    @Test void nativeDepotOrdinalDescribesFromStopIncludingRepeatedOccurrences() throws Exception {
        var repeated=route(List.of(a,b,a,c));var depot=new Depot(TransportMode.TRAIN,sim);
        depot.getRouteIds().add(repeated.getId());var map=new org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Route>();map.put(repeated.getId(),repeated);depot.writeRouteCache(map);
        var ordinal=Class.forName("org.mtr.core.data.VehicleExtraData$VehiclePlatformRouteInfo").getDeclaredField("platformIndexInRoute");ordinal.setAccessible(true);
        assertEquals(0,ordinal.getInt(depot.getVehiclePlatformRouteInfo(0)));
        assertEquals(1,ordinal.getInt(depot.getVehiclePlatformRouteInfo(1)));
        assertEquals(2,ordinal.getInt(depot.getVehiclePlatformRouteInfo(2)));
        // The corresponding next arrival occurrences are 1, 2, 3, even when platform A repeats.
        assertEquals(a.getId(),repeated.getRoutePlatforms().get(ordinal.getInt(depot.getVehiclePlatformRouteInfo(1))+1).getPlatform().getId());
    }
}

package io.github.teamcreating.mtrnav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mtr.core.data.*;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.libraries.com.google.gson.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real native rails, two independent depots/trains, intermediate stop and a same-station transfer. */
@EnabledIfSystemProperty(named="mtrnav.generateFixture", matches="true")
class RuntimeRideFixtureTest {
    private static Position pos(long x, long z) { return new Position(x, -59, z); }
    private static JsonObject point(long x, long z) {
        var p = new JsonObject(); p.addProperty("x", x); p.addProperty("y", -59); p.addProperty("z", z); return p;
    }
    private static Station station(Simulator sim, String name, int minX, int maxX, int minZ, int maxZ) {
        var s = new Station(sim); var j = new JsonObject(); j.addProperty("name", name); j.addProperty("color", 0xffffff);
        j.add("position1", point(minX, minZ)); j.add("position2", point(maxX, maxZ)); s.updateData(new JsonReader(j)); sim.stations.add(s); return s;
    }
    private static Rail rail(Simulator sim, long x1, long x2, long z, int type) {
        Rail r;
        if (type == 1) r = Rail.newPlatformRail(pos(x1,z), Angle.E, pos(x2,z), Angle.W, Rail.Shape.QUADRATIC,
                0,0,0,0,0,0,0,0,0,0,0,0,0,new ObjectArrayList<>(),TransportMode.TRAIN);
        else if (type == 2) r = Rail.newSidingRail(pos(x1,z), Angle.E, pos(x2,z), Angle.W, Rail.Shape.QUADRATIC,
                0,0,0,0,0,0,0,0,0,0,0,0,0,new ObjectArrayList<>(),TransportMode.TRAIN);
        else r = Rail.newRail(pos(x1,z), Angle.E, pos(x2,z), Angle.W, Rail.Shape.QUADRATIC,
                0,0,0,0,0,0,0,0,0,0,0,0,0,new ObjectArrayList<>(),60,60,false,false,true,false,true,TransportMode.TRAIN);
        assertTrue(r.isValid()); sim.rails.add(r); return r;
    }
    private static Platform platform(Simulator sim, long x, long z, String name) {
        var r = rail(sim,x,x+60,z,1); var p = new Platform(pos(x,z),pos(x+60,z),TransportMode.TRAIN,sim); p.rail=r;
        var j = new JsonObject(); j.addProperty("name",name); p.updateData(new JsonReader(j)); p.setDwellTime(25000); sim.platforms.add(p); return p;
    }
    private static Route route(Simulator sim, String name, int color, List<Platform> stops) {
        var r = new Route(TransportMode.TRAIN,sim); var j = new JsonObject(); j.addProperty("name",name); j.addProperty("color",color);
        var a = new JsonArray(); for (var p:stops) { var row = new JsonObject(); row.addProperty("platformId",p.getId()); a.add(row); }
        j.add("routePlatformData",a); r.updateData(new JsonReader(j));
        for(int i=0;i<stops.size();i++) r.getRoutePlatforms().get(i).platform=stops.get(i);
        sim.routes.add(r); return r;
    }
    private static Depot corridor(Simulator sim, String name, int color, long z, long[] stops) {
        var platforms = new ArrayList<Platform>();
        for(int i=0;i<stops.length;i++) {
            platforms.add(platform(sim,stops[i],z,name+"-"+(i+1)));
            if(i>0) rail(sim,stops[i-1]+60,stops[i],z,0);
        }
        long yardStart = stops[stops.length-1]+60, yardEnd=yardStart+100;
        var yardRail=rail(sim,yardStart,yardEnd,z,2);
        var siding = new Siding(pos(yardStart,z),pos(yardEnd,z),yardRail.railMath.getLength(),TransportMode.TRAIN,sim); siding.rail=yardRail;
        // Resource IDs, bogies and dimensions from the shipped 4.1.0-beta.2 vehicle definitions.
        siding.setVehicleCars(new ObjectArrayList<>(List.of(new VehicleCar("m_train_small_cab_1",20,2,100,-6,6,1,0),new VehicleCar("m_train_small_cab_2",20,2,100,-6,6,0,1))));
        siding.setMaxVehicles(1); sim.sidings.add(siding);
        var reverse=new ArrayList<>(platforms); Collections.reverse(reverse);
        var inbound=route(sim,name+" Return",color,reverse); var outbound=route(sim,name,color,platforms);
        var depot=new Depot(TransportMode.TRAIN,sim); var j=new JsonObject(); j.addProperty("name",name+" Depot"); j.addProperty("color",color);
        j.add("position1",point(yardStart-5,z-5));j.add("position2",point(yardEnd+5,z+5));
        var ids=new JsonArray();ids.add(inbound.getId());ids.add(outbound.getId());j.add("routeIds",ids);depot.updateData(new JsonReader(j));
        depot.routes.add(inbound);depot.routes.add(outbound);depot.setRepeatInfinitely(true);
        for(int hour=0;hour<24;hour++) depot.setFrequency(hour,60);
        sim.depots.add(depot); return depot;
    }
    @Test void generateRealTwoTrainTransferNetwork() throws Exception {
        try(var resources=getClass().getResourceAsStream("/assets/mtr/mtr_custom_resources.json")) {
            assertNotNull(resources);
            var json=JsonParser.parseString(new String(resources.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            var ids=new HashSet<String>();json.getAsJsonObject().getAsJsonArray("vehicles").forEach(v->ids.add(v.getAsJsonObject().get("id").getAsString()));
            assertTrue(ids.containsAll(List.of("m_train_small_cab_1","m_train_small_cab_2")));
        }
        var root=Path.of(System.getProperty("mtrnav.fixtureRoot"));
        assertFalse(Files.exists(root),"Refuse to overwrite a test world network");
        var sim=new Simulator("minecraft/overworld",new String[]{"minecraft/overworld"},root,false);
        station(sim,"QA Origin|起点",-10,70,-5,5);
        station(sim,"QA Middle|中途",140,220,-5,5);
        station(sim,"QA Transfer|换乘",290,370,-5,25);
        station(sim,"QA Destination|终点",590,670,15,25);
        var first=corridor(sim,"QA Red",0xe84b4b,0,new long[]{0,150,300});
        var second=corridor(sim,"QA Blue",0x3989ff,20,new long[]{300,600});
        sim.sync(); Depot.generateDepots(sim,new ObjectArrayList<>(List.of(first,second)));
        for(int i=0;i<5000;i++) sim.tick();
        assertFalse(first.getPath().isEmpty());assertFalse(second.getPath().isEmpty());
        sim.save();sim.stop();
        System.out.println("Real QA network saved: "+root+", red="+first.getLastGeneratedStatus()+", blue="+second.getLastGeneratedStatus());
    }
}

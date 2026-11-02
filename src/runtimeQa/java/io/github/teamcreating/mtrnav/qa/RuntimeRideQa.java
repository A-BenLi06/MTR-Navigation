package io.github.teamcreating.mtrnav.qa;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import io.github.teamcreating.mtrnav.client.*;
import io.github.teamcreating.mtrnav.core.Model;
import io.github.teamcreating.mtrnav.mixin.*;
import io.github.teamcreating.mtrnav.server.MtrCoreReader;
import io.github.teamcreating.mtrmap.extension.*;
import io.github.teamcreating.mtrmap.mapdata.MapDataCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.mtr.client.*;
import org.mtr.core.data.*;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.data.VehicleExtension;
import org.mtr.render.PositionAndRotation;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loopback-only dev CLI. Acts through game/native MTR APIs; never writes tracker or riding state. */
@Mod(value="mtrnavqa", dist=Dist.CLIENT)
public final class RuntimeRideQa {
    public static final java.util.concurrent.atomic.AtomicLong ridingPackets=new java.util.concurrent.atomic.AtomicLong(),coreUpdates=new java.util.concurrent.atomic.AtomicLong();
    public static volatile String lastCoreUpdate="";
    private static final Gson JSON=new GsonBuilder().serializeNulls().registerTypeHierarchyAdapter(Optional.class,
            (JsonSerializer<Optional<?>>)(value,type,context)->value.isPresent()?context.serialize(value.get()):JsonNull.INSTANCE).create();
    private final Path output=Path.of(System.getProperty("mtrnav.qa.output"));
    private volatile JsonObject nativeState=new JsonObject();
    private final AtomicBoolean reading=new AtomicBoolean();
    private int ticks; private KeyMapping held; private int releaseAt;
    public RuntimeRideQa() throws Exception {
        Files.createDirectories(output);
        var http=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),Integer.getInteger("mtrnav.qa.port",18761)),0);
        http.setExecutor(Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"mtrnav-local-qa");t.setDaemon(true);return t;}));
        http.createContext("/",exchange->{
            try {
                if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Local QA only");
                String path=exchange.getRequestURI().getPath();
                JsonObject request=exchange.getRequestMethod().equals("POST")?JsonParser.parseString(new String(exchange.getRequestBody().readNBytes(8192),StandardCharsets.UTF_8)).getAsJsonObject():new JsonObject();
                var result=new CompletableFuture<JsonElement>();
                Minecraft.getInstance().execute(()->{try {result.complete(path.equals("/state")?state():action(request));}catch(Throwable e){result.completeExceptionally(e);}});
                byte[] data=JSON.toJson(result.get(15,TimeUnit.SECONDS)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);
            } catch(Exception e) {
                byte[] data=JSON.toJson(Map.of("error",e.toString(),"cause",Objects.toString(e.getCause()))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,data.length);exchange.getResponseBody().write(data);
            } finally {exchange.close();}
        });
        http.start();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e)->tick());
        System.out.println("[RideQA] Local CLI ready on "+http.getAddress());
    }
    private void tick() {
        ticks++;var mc=Minecraft.getInstance();mc.options.pauseOnLostFocus=false;
        if(held!=null&&ticks>=releaseAt){held.setDown(false);held=null;}
        if(mc.player==null||mc.getSingleplayerServer()==null||ticks%10!=0||!reading.compareAndSet(false,true))return;
        try {
            Simulator sim=simulator(); if(sim==null){reading.set(false);return;}
            sim.run(()->{try {
                var data=new JsonObject();data.addProperty("sampleMillis",sim.getCurrentMillis());
                data.add("snapshot",JSON.toJsonTree(new MtrCoreReader().capture(sim,new Model.Settings(1,4,30))));
                var vehicles=new JsonArray();for(var siding:sim.sidings)siding.iterateVehicles(v->{var row=vehicle(v);row.add("riders",riders(v));vehicles.add(row);});
                data.add("vehicles",vehicles);nativeState=data;
            }catch(Throwable e){var failed=new JsonObject();failed.addProperty("error",e.toString());nativeState=failed;}finally{reading.set(false);}});
        }catch(Throwable e){reading.set(false);}
    }
    private static Simulator simulator() {
        var main=MtrAccessor.navigationMain();if(main==null)return null;
        return ((MainAccessor)main).navigationSimulators().stream().filter(s->s.dimension.equals("minecraft/overworld")).findFirst().orElse(null);
    }
    private static JsonArray riders(Vehicle v){var result=new JsonArray();v.vehicleExtraData.iterateRidingEntities(r->result.add(JSON.toJsonTree(Map.of("uuid",r.uuid.toString(),"onVehicle",r.isOnVehicle(),"car",r.getRidingCar()))));return result;}
    private static JsonObject vehicle(Vehicle v) {
        var row=new JsonObject();var x=v.vehicleExtraData;
        row.addProperty("id",Long.toHexString(v.getId()));row.addProperty("siding",Long.toHexString(x.getSidingId()));row.addProperty("moving",v.isMoving());row.addProperty("onRoute",v.getIsOnRoute());
        row.addProperty("route",Long.toHexString(x.getThisRouteId()));row.addProperty("routeName",x.getThisRouteName());row.addProperty("platform",Long.toHexString(x.getThisPlatformId()));
        row.addProperty("nextPlatform",Long.toHexString(x.getNextPlatformId()));row.addProperty("stopIndex",x.getStopIndex());row.addProperty("doors",x.getDoorMultiplier());row.addProperty("departureIndex",v.getDepartureIndex());
        var head=v.getHeadPositionAndTiltAngle();if(head!=null)row.add("head",JSON.toJsonTree(head.position()));
        return row;
    }
    private JsonObject state() {
        var mc=Minecraft.getInstance();var state=new JsonObject();state.addProperty("ticks",ticks);state.addProperty("screen",mc.screen==null?"":mc.screen.getClass().getName());
        state.addProperty("paused",mc.isPaused());state.addProperty("connected",mc.player!=null);state.addProperty("nativeMode",NavigationClient.usingNative);
        if(mc.player!=null){state.addProperty("uuid",mc.player.getUUID().toString());state.add("player",JSON.toJsonTree(Map.of("x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ(),"flying",mc.player.getAbilities().flying)));}
        state.addProperty("status",NavigationClient.status);state.addProperty("mapStatus",OverlaySupport.status());state.add("catalog",JSON.toJsonTree(NavigationClient.catalog));
        state.add("ridingDiagnostics",JSON.toJsonTree(Map.of("serverPackets",ridingPackets.get(),"coreUpdates",coreUpdates.get(),"lastCoreUpdate",lastCoreUpdate)));
        var fields=new JsonObject();
        for(String name:List.of("ridingVehicleId","ridingSidingId","ridingVehicleCarNumber","sendPositionUpdateTime"))try{var f=VehicleRidingMovement.class.getDeclaredField(name);f.setAccessible(true);fields.addProperty(name,f.get(null).toString());}catch(ReflectiveOperationException ignored){}
        state.add("nativeClientRidingFields",fields);
        state.add("journeys",JSON.toJsonTree(NavigationClient.journeys));state.add("tracking",JSON.toJsonTree(NavigationClient.tracking));state.add("native",nativeState);
        var clients=new JsonArray();for(var v:MinecraftClientData.getInstance().vehicles){var row=vehicle(v);row.addProperty("riding",VehicleRidingMovement.isRiding(v.getId()));row.addProperty("speed",v.getSpeed());clients.add(row);}state.add("clientVehicles",clients);
        try {
            var api=ClientOverlayRuntime.INSTANCE;var world=api.world("minecraft:overworld").orElse(null);state.add("overlayCatalog",world==null?JsonNull.INSTANCE:JSON.toJsonTree(api.catalog(world)));
            state.add("views",JSON.toJsonTree(api.views().active("minecraft:overworld")));
            var transformed=ViewRenderer.data(MapDataCache.get("minecraft/overworld"));state.add("renderPalette",JSON.toJsonTree(transformed.routePalette));
        }catch(Throwable e){state.addProperty("overlayError",e.toString());}
        return state;
    }
    private JsonElement action(JsonObject j) throws Exception {
        String op=j.get("op").getAsString();var mc=Minecraft.getInstance();
        switch(op) {
            case "resume"->{mc.setScreen(null);mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(6);mc.options.framerateLimit().set(60);}
            case "navigate"->NavigationClient.open();
            case "query"->{NavigationClient.origin=j.get("origin").getAsString();NavigationClient.destination=j.get("destination").getAsString();NavigationClient.query();}
            case "select"->NavigationClient.select(NavigationClient.journeys.get(j.has("index")?j.get("index").getAsInt():0));
            case "cancel"->NavigationClient.cancel();
            case "command"->{String cmd=j.get("command").getAsString();var server=mc.getSingleplayerServer();if(server==null)throw new IllegalStateException("Singleplayer only");server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),cmd));}
            case "key"->{String key=j.get("name").getAsString();held=Arrays.stream(mc.options.keyMappings).filter(k->k.getName().equals(key)).findFirst().orElseThrow();held.setDown(true);KeyMapping.click(held.getKey());releaseAt=ticks+(j.has("ticks")?j.get("ticks").getAsInt():2);}
            case "keys"->{return JSON.toJsonTree(Arrays.stream(mc.options.keyMappings).map(k->Map.of("name",k.getName(),"key",k.getTranslatedKeyMessage().getString())).toList());}
            case "scroll"->{if(mc.screen==null)throw new IllegalStateException("Open a map first");mc.screen.mouseScrolled(mc.getWindow().getGuiScaledWidth()/2.0,mc.getWindow().getGuiScaledHeight()/2.0,0,j.get("amount").getAsDouble());}
            case "hoverCorner"->{if(mc.screen!=null){var move=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);move.setAccessible(true);move.invoke(mc.mouseHandler,mc.getWindow().getWindow(),4.0,50.0);}}
            case "door"->{return doorway(j);}
            case "screenshot"->{String name=j.get("name").getAsString();if(!name.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Simple screenshot name required");try(var picture=Screenshot.takeScreenshot(mc.getMainRenderTarget())){picture.writeToFile(output.resolve(name+".png"));}}
            case "look"->{mc.player.setYRot(j.get("yaw").getAsFloat());mc.player.setXRot(j.get("pitch").getAsFloat());}
            case "quit"->mc.stop();
            default->throw new IllegalArgumentException("Unknown operation "+op);
        }
        Files.writeString(output.resolve("actions.jsonl"),JSON.toJson(j)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        return JSON.toJsonTree(Map.of("ok",true,"op",op));
    }
    private JsonElement doorway(JsonObject j) {
        long id=Long.parseUnsignedLong(j.get("vehicle").getAsString(),16);
        var v=MinecraftClientData.getInstance().vehicles.stream().filter(vehicle->vehicle.getId()==id).findFirst().orElseThrow();
        var cars=v.getVehicleCarsAndPositions();var car=cars.getFirst();var bogies=new ObjectArrayList<PositionAndRotation>();
        for(var b:car.right())bogies.add(new PositionAndRotation(b.positionAndTiltAngle1().position(),b.positionAndTiltAngle2().position(),b.positionAndTiltAngle1().tiltAngle(),b.positionAndTiltAngle2().tiltAngle(),true));
        var pose=new PositionAndRotation(bogies,car.left(),true);var result=new JsonObject();
        CustomResourceLoader.getVehicleById(TransportMode.TRAIN,car.left().getVehicleId(),resource->{
            var cache=resource.left().getCachedVehicleResource(0,cars.size());
            if(cache!=null){result.add("floors",JSON.toJsonTree(cache.floors));result.add("doorways",JSON.toJsonTree(cache.doorways));
                if(j.has("teleport")&&j.get("teleport").getAsBoolean()) {
                    var door=cache.doorways.get(j.has("doorIndex")?j.get("doorIndex").getAsInt():0);
                    var local=new Vec3((door.minX+door.maxX)/2,door.minY+.1,(door.minZ+door.maxZ)/2);
                    var world=pose.transformForwards(local,Vec3::xRot,Vec3::yRot,Vec3::zRot,Vec3::add);
                    var mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();var uuid=mc.player.getUUID();
                    server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);player.teleportTo(world.x,world.y,world.z);});
                    result.add("teleport",JSON.toJsonTree(world));
                }
            }
        });
        if(result.isEmpty())throw new IllegalStateException("Real vehicle resource/cache not available: "+car.left().getVehicleId());
        return result;
    }
}

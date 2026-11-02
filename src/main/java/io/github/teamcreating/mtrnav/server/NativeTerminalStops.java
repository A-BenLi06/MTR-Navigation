package io.github.teamcreating.mtrnav.server;

import io.github.teamcreating.mtrnav.core.Model.*;
import org.mtr.core.data.*;
import java.lang.reflect.Field;
import java.util.*;

/** Pinned Core native schedules: Arrivals intentionally omits merged and repeating terminal stops. */
final class NativeTerminalStops {
    private final Field sidingTrips, tripStops;
    NativeTerminalStops() throws ReflectiveOperationException {
        sidingTrips=Siding.class.getDeclaredField("trips");sidingTrips.setAccessible(true);
        tripStops=Trip.class.getDeclaredField("stopTimes");tripStops.setAccessible(true);
    }
    @SuppressWarnings("unchecked")
    void append(Siding siding, List<StopEvent> events, int first) {
        try {
            var trips=(List<Trip>)sidingTrips.get(siding);
            var groups=new LinkedHashMap<RunKey,List<StopEvent>>();
            for(int i=first;i<events.size();i++)groups.computeIfAbsent(events.get(i).run(),ignored->new ArrayList<>()).add(events.get(i));
            for(var entry:groups.entrySet()) {
                int ordinal=entry.getKey().tripIndex();if(ordinal<0||ordinal>=trips.size())continue;
                var trip=trips.get(ordinal);var platforms=trip.route.getRoutePlatforms();int terminal=platforms.size()-1;
                if(terminal<1||entry.getValue().stream().anyMatch(e->e.stopIndex()==terminal))continue;
                var stops=(List<Trip.StopTime>)tripStops.get(trip);
                if(stops.isEmpty()||stops.size()>platforms.size())continue;
                boolean valid=true;
                for(int i=0;i<stops.size();i++)if(platforms.get(i).getPlatform()==null||stops.get(i).tripStopIndex!=i||stops.get(i).platformId!=platforms.get(i).getPlatform().getId()){valid=false;break;}
                if(!valid)continue;
                Trip.StopTime last=null;
                if(stops.size()==platforms.size())last=stops.getLast();
                else if(stops.size()==terminal&&ordinal+1<trips.size()) {
                    var following=(List<Trip.StopTime>)tripStops.get(trips.get(ordinal+1));
                    if(!following.isEmpty()&&platforms.getLast().getPlatform()!=null&&following.getFirst().platformId==platforms.getLast().getPlatform().getId())last=following.getFirst();
                }
                if(last==null)continue;
                var anchor=entry.getValue().stream().filter(e->e.stopIndex()>=0&&e.stopIndex()<stops.size()
                        && e.platformId().equals(Long.toHexString(stops.get(e.stopIndex()).platformId))).findFirst().orElse(null);
                if(anchor==null)continue;
                long offset=anchor.arrival()-stops.get(anchor.stopIndex()).startTime;
                events.add(new StopEvent(anchor.run(),anchor.vehicleId(),anchor.routeId(),Long.toHexString(last.platformId),terminal,
                        trip.route.getDestination(terminal),offset+last.startTime,offset+last.endTime,anchor.deviation(),anchor.realtime()));
            }
        }catch(IllegalAccessException e){throw new IllegalStateException("Cannot read pinned native terminal schedule",e);}
    }
}

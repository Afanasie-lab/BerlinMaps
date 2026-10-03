package GTFS;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class GTFSData {

	public HashMap<String, Stop> stops;
	public HashMap<String, RouteInfo> routes;
	public HashMap<String, Trip> trips;
	public HashMap<String, List<StopTime>> stopIndex = new HashMap<>();


	public GTFSData() {
		stops = new HashMap<>();
		trips = new HashMap<>();
		routes = new HashMap<>();
	}

	public void addTrip(String key, Trip value) {
		trips.put(key, value);
	}


	public HashMap<String, Trip> getTrips() {
		return trips;
	}

	public Trip getTrip(String key) {
		return trips.get(key);
	}

	public void addStop(String key, Stop stop) {
		stops.put(key, stop);
	}

	public void addRoute(String key, RouteInfo routeInfo){
		routes.put(key, routeInfo);
	}

	public RouteInfo getRoute(String routeId) {
    	return routes.get(routeId);
    }


	public void buildStopIndex() {
		for (Trip trip : trips.values()) {
			for (StopTime st : trip.getStopTimes()) {
				stopIndex.computeIfAbsent(st.stop_id, k -> new ArrayList<>()).add(st);
			}
		}
	}


}

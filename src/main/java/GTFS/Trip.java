package GTFS;

import java.util.ArrayList;
import java.util.Comparator;

public class Trip {
	String tripId;
	public String routeId;
	public String headSign;
	ArrayList<StopTime> stopList = new ArrayList<StopTime>();

	public Trip(String tripId, String routeId, String headString) {
		this.tripId = tripId;
		this.routeId = routeId;
		this.headSign = headString;
	}	
	
	public void addStopTime(StopTime time) {
		stopList.add(time);
	}

	public ArrayList<StopTime> getStopTimes() {
		return stopList;
	}

	public void sortStopTimes() {
		stopList.sort(Comparator.comparingInt(st -> st.stopSequence));
	}


}

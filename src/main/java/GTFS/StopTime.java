package GTFS;

public class StopTime {
    public final String tripId;
    public final String stop_id;
    public final int arrivalTime;
    public final int stopSequence;

    public StopTime(String tripId, String stop_id, int arrivalTime, int stopSequence) {
        this.tripId = tripId;
        this.stop_id = stop_id;
        this.arrivalTime = arrivalTime;
        this.stopSequence = stopSequence;
    }
}

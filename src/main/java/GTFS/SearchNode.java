package GTFS;

public class SearchNode {
    public Stop stop;
    public int time;
    public SearchNode parent;
    public String mode;
    public Trip trip;

    public SearchNode(Stop stop, int time, SearchNode parent, String mode) {
        this.stop = stop;
        this.time = time;
        this.parent = parent;
        this.mode = mode;
        this.trip = null;
    }

    public SearchNode(Stop stop, int time, SearchNode parent, String mode, Trip trip) {
        this.stop = stop;
        this.time = time;
        this.parent = parent;
        this.mode = mode;
        this.trip = trip;
    }
}

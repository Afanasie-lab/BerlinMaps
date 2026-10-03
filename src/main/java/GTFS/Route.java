package GTFS;

// Holds parsed row from routes.txt
public class Route {
    public final String routeId;     // route_id
    public final String agencyId;    // agency_id
    public final String shortName;   // route_short_name
    public final String longName;    // route_long_name

    public Route(String routeId, String agencyId, String shortName, String longName) {
        this.routeId = routeId;
        this.agencyId = agencyId;
        this.shortName = shortName;
        this.longName = longName;
    }
}

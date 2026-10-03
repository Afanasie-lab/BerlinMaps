package GTFS;

import java.util.Map;

public class RouteInfo {
    public final String operatorName;
    public final String shortName;
    public final String longName;

    public RouteInfo(String operatorName, String shortName, String longName) {
        this.operatorName = operatorName;
        this.shortName = shortName;
        this.longName = longName;
    }

    public Map<String, String> toMap(String headSign) {
        return Map.of(
            "operator",  operatorName,
            "shortName", shortName,
            "longName",  longName,
            "headSign",  headSign
        );
    }
}


package GTFS;

// Holds parsed row from agency.txt
public class Agency {
    public final String agencyId;    // agency_id
    public final String agencyName;  // agency_name

    public Agency(String agencyId, String agencyName) {
        this.agencyId = agencyId;
        this.agencyName = agencyName;
    }
}

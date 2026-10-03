package Structures;

public class Coordinates {

    private double longitude;
    private double latitude;

    public Coordinates(double latitude, double longitude){
        this.longitude=longitude;
        this.latitude=latitude;
    }

    public double getLatitude(){
        return latitude;
    }
    public double getLongitude(){
        return longitude;
    }
}

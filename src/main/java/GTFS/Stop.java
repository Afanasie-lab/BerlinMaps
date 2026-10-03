package GTFS;

import java.util.HashMap;

public class Stop {
	public String stop_id;
    public String stop_name;
    public double stop_lat;
    public double stop_lon;
	HashMap<String, Object> extra_fields; 

	public Stop() {
		extra_fields = new HashMap<>();
	}

	public static Stop CSVToRow(String[] headers, String[] values) throws InvalidStopException {
		if (values.length != headers.length) 
			throw new InvalidStopException("Column count mismatch");

		Stop stop = new Stop();

		for (int i = 0; i < headers.length; i++) {
			String header = headers[i].trim();
			String value = values[i].trim();
			
			switch (header) {
				case "stop_id"   -> stop.stop_id = value;
				case "stop_name" -> stop.stop_name = value;
				case "stop_lat" -> {
					try {
						stop.stop_lat = Double.parseDouble(value);
					} catch (NumberFormatException e) {
						throw new InvalidStopException("Invalid stop_lat value: '" + value + "' for stop_id: " + stop.stop_id);
					}
				}
				case "stop_lon" -> {
					try {
						stop.stop_lon = Double.parseDouble(value);
					} catch (NumberFormatException e) {
						throw new InvalidStopException("Invalid stop_lon value: '" + value + "' for stop_id: " + stop.stop_id);
					}
				}
				default -> stop.extra_fields.put(header, value);
			}
		}

		if (stop.stop_id == null || stop.stop_id.isEmpty())
			throw new InvalidStopException("Missing stop_id");

		return stop;
	}

}

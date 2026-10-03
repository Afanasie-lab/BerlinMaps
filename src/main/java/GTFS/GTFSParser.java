package GTFS;

import java.util.List;
import java.util.Map;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class GTFSParser {
	
	public GTFSParser() {}

	private BufferedReader getZipEntry(ZipFile zip, String name) throws Exception {

		ZipEntry entry = zip.getEntry(name);

		if (entry == null) {
			Enumeration<? extends ZipEntry> entries = zip.entries();

			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();

				if (e.getName().endsWith("/" + name)
						|| e.getName().equals(name)) {

					entry = e;
					break;
				}
			}
		}

		if (entry == null)
			throw new Exception("Missing " + name + " in GTFS zip");

		return new BufferedReader(
			new InputStreamReader(zip.getInputStream(entry)));
	}

	public GTFSData parse(String filename) throws Exception {

		// System.err.println("Trying to open: [" + filename + "]");
		File f = new File(filename);	
		//System.err.println("File exists: " + f.exists());
		// System.err.println("Absolute path: " + f.getAbsolutePath());
		if (!f.exists()) throw new Exception("File not found: " + filename);

		GTFSData data = new GTFSData();

		String line;

		try (ZipFile zip = new ZipFile(f)) {
			BufferedReader stops = getZipEntry(zip, "stops.txt");
			String[] headers = null;

			while ((line = stops.readLine()) != null) {
				if (headers == null) {
        			headers = parseCSVLine(line); 
					continue;
				}

    			String[] values = parseCSVLine(line); 
				try {
					Stop stop = Stop.CSVToRow(headers, values);
					data.addStop(stop.stop_id, stop);
				} catch (InvalidStopException e) {
					System.err.println("Skipping invalid stop: " + e.getMessage());
				}

			}
		

		// Parse agency.txt
			HashMap<String, String> agencyNames = new HashMap<>();
			try {
				BufferedReader agencyReader = getZipEntry(zip, "agency.txt");
				String[] agencyHeaders = null;
				while ((line = agencyReader.readLine()) != null) {
					if (agencyHeaders == null) {
						agencyHeaders = parseCSVLine(line);
						continue;
					}
					String[] values = parseCSVLine(line);
					String agencyId = "";
					String agencyName = "";
					for (int i = 0; i < agencyHeaders.length; i++) {
						if (agencyHeaders[i].equals("agency_id"))   agencyId   = values[i];
						if (agencyHeaders[i].equals("agency_name")) agencyName = values[i];
					}
					agencyNames.put(agencyId, agencyName);
				}
			} catch (Exception e) {
				System.err.println("Could not parse agency.txt: " + e.getMessage());
			}



		// Parse routes.txt
			HashMap<String, String[]> routeData = new HashMap<>();
			try {
				BufferedReader routeReader = getZipEntry(zip, "routes.txt");
				String[] routeHeaders = null;
				while ((line = routeReader.readLine()) != null) {
					if (routeHeaders == null) {
						routeHeaders = parseCSVLine(line);
						continue;
					}
					String[] values = parseCSVLine(line);
					String routeId = "";
					String agencyId = "";
					String shortName = "";
					String longName = "";
					for (int i = 0; i < routeHeaders.length; i++) {
						if (routeHeaders[i].equals("route_id"))         routeId   = values[i];
						if (routeHeaders[i].equals("agency_id"))        agencyId  = values[i];
						if (routeHeaders[i].equals("route_short_name")) shortName = values[i];
						if (routeHeaders[i].equals("route_long_name"))  longName  = values[i];
					}
					if (!routeId.isEmpty()) {
						routeData.put(routeId, new String[]{agencyId, shortName, longName});
					}
				}
			} catch (Exception e) {
				System.err.println("Could not parse routes.txt: " + e.getMessage());
			}


		// Parse trips.txt
		try {
			BufferedReader tripReader = getZipEntry(zip, "trips.txt");
			String[] tripHeaders = null;
			int colTripId = -1, colRouteId = -1, colHeadSign = -1;
			while ((line = tripReader.readLine()) != null) {
				if (tripHeaders == null) {
					tripHeaders = parseCSVLine(line);
					for (int i = 0; i < tripHeaders.length; i++) {
						switch (tripHeaders[i]) {
							case "trip_id"       -> colTripId   = i;
							case "route_id"      -> colRouteId  = i;
							case "trip_headsign" -> colHeadSign = i;
						}
					}
					continue;
				}
				String[] values = parseCSVLine(line);
				String tripId   = colTripId   >= 0 && colTripId   < values.length ? values[colTripId]   : "";
				String routeId  = colRouteId  >= 0 && colRouteId  < values.length ? values[colRouteId]  : "";
				String headSign = colHeadSign >= 0 && colHeadSign < values.length ? values[colHeadSign] : "";
				if (!tripId.isEmpty() && !routeId.isEmpty()) {
					data.addTrip(tripId, new Trip(tripId, routeId, headSign));
				}
			}
		} catch (Exception e) {
			System.err.println("Could not parse trips.txt: " + e.getMessage());
		}



		// Parse stop_times.txt
		try {
			BufferedReader stopTimeReader = getZipEntry(zip, "stop_times.txt");
			String[] stopTimeHeaders = null;
			int colStTrip = -1, colStStop = -1, colStArr = -1, colStSeq = -1;
			while ((line = stopTimeReader.readLine()) != null) {
				if (stopTimeHeaders == null) {
					stopTimeHeaders = parseCSVLine(line);
					for (int i = 0; i < stopTimeHeaders.length; i++) {
						switch (stopTimeHeaders[i]) {
							case "trip_id"       -> colStTrip = i;
							case "stop_id"       -> colStStop = i;
							case "arrival_time"  -> colStArr  = i;
							case "stop_sequence" -> colStSeq  = i;
						}
					}
					continue;
				}
				String[] values = parseCSVLine(line);
				String tripId         = colStTrip >= 0 && colStTrip < values.length ? values[colStTrip] : "";
				String stopId         = colStStop >= 0 && colStStop < values.length ? values[colStStop] : "";
				String arrivalTimeStr = colStArr  >= 0 && colStArr  < values.length ? values[colStArr]  : "";
				String stopSequenceStr= colStSeq  >= 0 && colStSeq  < values.length ? values[colStSeq]  : "";
				Trip trip = data.getTrip(tripId);
				if (trip == null) continue;
				try {
					int arrivalTime = parseTime(arrivalTimeStr);
					int stopSequence = Integer.parseInt(stopSequenceStr);
					trip.addStopTime(new StopTime(tripId, stopId, arrivalTime, stopSequence));
				} catch (Exception e) {
					System.err.println("Skipping invalid stop_time: " + e.getMessage());
				}
			}
		} catch (Exception e) {
			System.err.println("Could not parse stop_times.txt: " + e.getMessage());
		}

		for (Trip trip : data.trips.values()) {
			trip.sortStopTimes();
		}

		
		for (String routeId : routeData.keySet()) {
				String[] info = routeData.get(routeId);
				String agencyId  = info[0];
				String shortName = info[1];
				String longName  = info[2];

				String agencyName = agencyNames.get(agencyId);
				if (agencyName == null) agencyName = "";


				RouteInfo routeInfo = new RouteInfo(agencyName, shortName, longName);
				data.addRoute(routeId, routeInfo);
			}

			data.buildStopIndex();

			return data;
		}
	}
	
	
	private String[] parseCSVLine(String line) {
		List<String> fields = new ArrayList<>();
		boolean inQuotes = false;
		StringBuilder current = new StringBuilder();
		for (char c : line.toCharArray()) {
			if (c == '"') {
				inQuotes = !inQuotes;
			} else if (c == ',' && !inQuotes) {
				fields.add(current.toString().trim());
				current = new StringBuilder();
			} else {
				current.append(c);
			}
		}
		fields.add(current.toString().trim());
		return fields.toArray(new String[0]);
	}

	public int parseTime(String time) {
		String[] parts = time.split(":");
		int hours   = Integer.parseInt(parts[0].trim());
		int minutes = Integer.parseInt(parts[1].trim());
		int seconds = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
		return hours * 3600 + minutes * 60 + seconds;
	}

	public String secondsToHHMM(int seconds) {
		return String.format("%02d:%02d", seconds / 3600, (seconds % 3600) / 60);
	}
}


package routing; 

import java.awt.Color;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.text.ParseException;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import com.leastfixedpoint.json.JSONReader;
import com.leastfixedpoint.json.JSONSyntaxError;
import com.leastfixedpoint.json.JSONWriter;

import GTFS.ClosureAnalysis;
import GTFS.GTFSData;
import GTFS.GTFSParser;
import GTFS.RouteInfo;
import GTFS.SearchNode;
import GTFS.Stop;
import GTFS.StopTime;
import GTFS.Trip;
import util.RequestHandler;
import util.RequestValidator;
import Window.Window;
import Structures.Coordinates;

public class RoutingEngine {
	private Window window = null; 
 	private static String window_title = "Yahoo Maps";
 	private static int window_WIDTH = 900, window_HEIGHT = 600;
 	private static Color window_backgroundColor = new Color(255, 0, 0);
	private GTFSParser parser = new GTFSParser();
	private static final double R = 6371.0;
    private JSONWriter<OutputStreamWriter> responseWriter =
        new JSONWriter<>(new OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8));
	private static final double MAX_WALK_KM = 1.0;
	private static final int TRANSFER_PENALTY_SECONDS = 5 * 60;

	private GTFSData gtfsData = null;

	BufferedReader lineReader = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));

	public void setWindow(Window w) { this.window = w; }

    public static void main(String[] args) throws Exception {
		RoutingEngine engine = new RoutingEngine();
		Window w = new Window();
		engine.setWindow(w);
		javax.swing.SwingUtilities.invokeAndWait(() ->
			w.init(window_title, window_WIDTH, window_HEIGHT, window_backgroundColor));
		engine.run();
    }
	

    public void run() throws IOException, Exception {
        System.err.println("Starting");
		while (true) {
			Map<?, ?> request = readInput();
			if (request == null) break;

			RequestHandler handler = null;
			for (String key : handlers.keySet()) {
				if (request.containsKey(key)) { handler = handlers.get(key); break; }
			}

			if (handler == null) { sendError("Bad request"); continue; }

			try {
				handler.handle(request);
			} catch (Exception e) {
				sendError(e.getMessage());
				if (request.containsKey("load")) break; 
			}
		}
    }


    private Coordinates parsePoint(Map<?, ?> points){

        double lat=((Number)points.get("lat")).doubleValue();

        double lon=((Number)points.get("lon")).doubleValue();

        return new Coordinates(lat, lon);
    }


	private final Map<String, RequestHandler> handlers = Map.of(
		"ping", request -> {
			sendOk(Map.of("pong", request.get("ping")));
		},
		"closure_analysis", request -> {
			String outputPath = request.containsKey("output") 
				? (String) request.get("output") 
				: "closure_analysis.csv";
			System.err.println("gtfsData is: " + gtfsData);
			ClosureAnalysis analysis = new ClosureAnalysis(this, gtfsData);
			analysis.runAndWriteCSV(outputPath);
			sendOk("closure_analysis_complete");
		},
		"load", request -> {
			String filename = RequestValidator.extractFilename(request.get("load"));
			long start = System.currentTimeMillis();
			gtfsData = parser.parse(filename);

			if (window != null) window.loadGTFSData(gtfsData, filename);
			long elapsed = System.currentTimeMillis() - start;
			System.err.println("Loaded in " + elapsed + "ms");
			System.err.println("Stops: " + gtfsData.stops.size());
			System.err.println("Trips: " + gtfsData.trips.size());
			System.err.println("Routes: " + gtfsData.routes.size());
			if (elapsed > 120000) throw new Exception("Load exceeded 2 minute limit");
			sendOk("loaded");
		},
		"routeFrom", request -> {
			Coordinates from = RequestValidator.extractPoint(request.get("routeFrom"), "routeFrom");
			Coordinates to   = RequestValidator.extractPoint(request.get("to"), "to");
			String time   = RequestValidator.extractString(request.get("startingAt"), "startingAt");
			sendOk(computeRoute(from, to, time, null));
		}
	);

	private Map<?, ?> readInput() throws IOException {
		String line = lineReader.readLine();
		if (line == null) return null;
		for (char c : line.toCharArray()) {
			if (c == '�' || (c < 32 && c != '\t')) {
				sendError("Bad character in input: U+" + String.format("%04X", (int) c));
				return null;
			}
		}

		try {

			Object json = new JSONReader(new StringReader(line)).read();
			if (json instanceof Map<?, ?>) return (Map<?, ?>) json;

			sendError("Bad request");
			return null;

		} catch (JSONSyntaxError e) {
			sendError("Bad JSON input");
			return null;
		}

	}

	private List<?> computeRoute(Coordinates from, Coordinates to, String startingAt, Set<String> excludedStopIds) throws Exception {

		if (gtfsData == null)
			throw new Exception("No GTFS data loaded");

		int startSeconds = parser.parseTime(startingAt);

		PriorityQueue<SearchNode> pq = new PriorityQueue<>(
			Comparator.comparingInt((SearchNode n) -> n.time + walkMinutes(stopCoords(n.stop), to) * 60)
		);
		HashMap<String, Integer> bestTime = new HashMap<>();

		for (Stop stop : gtfsData.stops.values()) {
			if (excludedStopIds != null && excludedStopIds.contains(stop.stop_id)) continue;
			double dist = haversine(from, stopCoords(stop));
			if (dist <= MAX_WALK_KM) {
				int arrivalTime = startSeconds + walkMinutes(from, stopCoords(stop)) * 60;
				SearchNode node = new SearchNode(stop, arrivalTime, null, "walk", null);
				pq.add(node);
				bestTime.put(stop.stop_id, arrivalTime);
			}
		}

		SearchNode bestGoal = null;
		int bestTotal = Integer.MAX_VALUE;

		while (!pq.isEmpty()) {
			SearchNode current = pq.poll();

			Integer best = bestTime.get(current.stop.stop_id);
			if (best != null && current.time > best) continue;

			double distToDest = haversine(stopCoords(current.stop), to);
			if (distToDest <= MAX_WALK_KM) {
				int totalTime = current.time + walkMinutes(stopCoords(current.stop), to) * 60;
				if (totalTime < bestTotal) {
					bestTotal = totalTime;
					bestGoal = new SearchNode(new Stop(), totalTime, current, "walk", null);
					bestGoal.stop.stop_lat = to.getLatitude();
					bestGoal.stop.stop_lon = to.getLongitude();
					bestGoal.stop.stop_name = "destination";
				}
			}

			if (current.time >= bestTotal) break;

			List<StopTime> departures = gtfsData.stopIndex.get(current.stop.stop_id);
			if (departures == null) continue;

			for (StopTime st : departures) {
				if (st.arrivalTime < current.time) continue;
				Trip trip = gtfsData.getTrip(st.tripId);
				if (trip == null) continue;

				ArrayList<StopTime> stops = trip.getStopTimes();
				boolean boarding = false;
				for (StopTime next : stops) {
					if (next.stop_id.equals(st.stop_id)) { boarding = true; continue; }
					if (!boarding) continue;
					if (excludedStopIds != null && excludedStopIds.contains(next.stop_id)) break;

					Stop nextStop = gtfsData.stops.get(next.stop_id);
					if (nextStop == null) continue;

					boolean isTransfer = current.trip != null && !current.trip.equals(trip);
					int effectiveTime = next.arrivalTime + (isTransfer ? TRANSFER_PENALTY_SECONDS : 0);
					if (!bestTime.containsKey(next.stop_id) || effectiveTime < bestTime.get(next.stop_id)) {
						bestTime.put(next.stop_id, effectiveTime);
						pq.add(new SearchNode(nextStop, effectiveTime, current, "ride", trip));
					}
				}
			}
		}

		if (bestGoal == null) throw new Exception("No route found");

		return reconstructPath(bestGoal, from, startingAt);
	}


	public Map<String, Integer> computeAllRoutes(Coordinates from, int startSeconds, Set<String> excludedStopIds, Set<String> excludedRouteIds) {
		if (gtfsData == null) return new HashMap<>();

		PriorityQueue<SearchNode> pq = new PriorityQueue<>(
			Comparator.comparingInt((SearchNode n) -> n.time)
		);
		HashMap<String, Integer> bestTime = new HashMap<>();

		for (Stop stop : gtfsData.stops.values()) {
			if (excludedStopIds != null && excludedStopIds.contains(stop.stop_id)) continue;
			double dist = haversine(from, stopCoords(stop));
			if (dist <= MAX_WALK_KM) {
				int arrivalTime = startSeconds + walkMinutes(from, stopCoords(stop)) * 60;
				bestTime.put(stop.stop_id, arrivalTime);
				pq.add(new SearchNode(stop, arrivalTime, null, "walk", null));
			}
		}

		while (!pq.isEmpty()) {
			SearchNode current = pq.poll();

			Integer best = bestTime.get(current.stop.stop_id);
			if (best != null && current.time > best) continue;

			List<StopTime> departures = gtfsData.stopIndex.get(current.stop.stop_id);
			if (departures == null) continue;

			for (StopTime st : departures) {
				if (st.arrivalTime < current.time) continue;

				Trip trip = gtfsData.getTrip(st.tripId);
				if (trip == null) continue;
				if (excludedRouteIds != null && excludedRouteIds.contains(trip.routeId)) continue;

				ArrayList<StopTime> stops = trip.getStopTimes();
				boolean boarding = false;
				for (StopTime next : stops) {
					if (next.stop_id.equals(st.stop_id)) { boarding = true; continue; }
					if (!boarding) continue;
					if (excludedStopIds != null && excludedStopIds.contains(next.stop_id)) break;

					Stop nextStop = gtfsData.stops.get(next.stop_id);
					if (nextStop == null) continue;

					boolean isTransfer = current.trip != null && !current.trip.equals(trip);
					int effectiveTime = next.arrivalTime + (isTransfer ? TRANSFER_PENALTY_SECONDS : 0);

					if (!bestTime.containsKey(next.stop_id) || effectiveTime < bestTime.get(next.stop_id)) {
						bestTime.put(next.stop_id, effectiveTime);
						pq.add(new SearchNode(nextStop, effectiveTime, current, "ride", trip));
					}
				}
			}
		}

		return bestTime;
	}

	private List<Map<String, Object>> reconstructPath(SearchNode goal, Coordinates from, String startingAt) {
		ArrayList<SearchNode> chain = new ArrayList<>();
		SearchNode cur = goal;
		while (cur != null) { chain.add(cur); cur = cur.parent; }
		Collections.reverse(chain);

		ArrayList<Map<String, Object>> steps = new ArrayList<>();
		SearchNode first = chain.get(0);
		steps.add(Map.of(
			"mode", "walk",
			"to", Map.of("lat", first.stop.stop_lat, "lon", first.stop.stop_lon),
			"duration", walkMinutes(from, stopCoords(first.stop)),
			"startTime", startingAt
		));

		for (int i = 1; i < chain.size(); i++) {
			SearchNode node = chain.get(i);
			SearchNode prev = chain.get(i - 1);
			int duration = (node.time - prev.time) / 60;

			if (node.mode.equals("walk")) {
				steps.add(Map.of(
					"mode", "walk",
					"to", Map.of("lat", node.stop.stop_lat, "lon", node.stop.stop_lon),
					"duration", duration,
					"startTime", parser.secondsToHHMM(prev.time)
				));
			} else {
				RouteInfo route = gtfsData.getRoute(node.trip.routeId);
				List<Map<String, Object>> waypoints = new ArrayList<>();
				ArrayList<StopTime> tripStops = node.trip.getStopTimes();
				boolean inSegment = false;
				for (StopTime st : tripStops) {
					if (st.stop_id.equals(prev.stop.stop_id)) { inSegment = true; continue; }
					if (!inSegment) continue;
					Stop s = gtfsData.stops.get(st.stop_id);
					if (s != null) waypoints.add(Map.of("lat", s.stop_lat, "lon", s.stop_lon));
					if (st.stop_id.equals(node.stop.stop_id)) break;
				}
				steps.add(Map.of(
					"mode", "ride",
					"to", Map.of("lat", node.stop.stop_lat, "lon", node.stop.stop_lon),
					"waypoints", waypoints,
					"duration", duration,
					"startTime", parser.secondsToHHMM(prev.time),
					"stop", node.stop.stop_name,
					"route", route.toMap(node.trip.headSign)
				));
			}
		}
		return steps;
	}


	private double haversine(Coordinates a, Coordinates b) {

		double delta_lat = Math.toRadians(b.getLatitude()  - a.getLatitude());
		double delta_lon = Math.toRadians(b.getLongitude() - a.getLongitude());
		double x = Math.pow(Math.sin(delta_lat / 2), 2)
				 + Math.cos(Math.toRadians(a.getLatitude()))
				 * Math.cos(Math.toRadians(b.getLatitude()))
				 * Math.pow(Math.sin(delta_lon / 2), 2);
		double duration = R * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
		return duration;
	}

	private int walkMinutes(Coordinates a, Coordinates b) {
		return (int) Math.round((haversine(a, b) / 5.0) * 60);
	}

	private Coordinates stopCoords(Stop s) {
		return new Coordinates(s.stop_lat, s.stop_lon);
	}


    private void sendOk(Object value) throws IOException {
        responseWriter.write(Map.of("ok", value));
        responseWriter.getWriter().write('\n');
        responseWriter.getWriter().flush();
    }

    private void sendError(String message) throws IOException {
        responseWriter.write(Map.of("error", message));
        responseWriter.getWriter().write('\n');
        responseWriter.getWriter().flush();
    }

	public void loadData(GTFSData data) {
		this.gtfsData = data;
	}

	public List<?> computeRoutePublic(Coordinates from, Coordinates to, String startingAt, Set<String> excludedStopIds) throws Exception {
		return computeRoute(from, to, startingAt, excludedStopIds);
	}
}


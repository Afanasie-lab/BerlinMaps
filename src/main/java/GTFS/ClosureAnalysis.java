package GTFS;

import Structures.Coordinates;
import routing.RoutingEngine;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class ClosureAnalysis {
    public RoutingEngine engine;
    public GTFSData data;

    private static final int NUM_ORIGINS = 10;
    private static final int NUM_SAMPLE_STOPS = 500;
    private static final int START_SECONDS = 8 * 3600;
    private static final int UNREACHABLE_PENALTY = 3600;

    public ClosureAnalysis(RoutingEngine engine, GTFSData data) {
        this.engine = engine;
        this.data = data;
    }

    public void runAndWriteCSV(String outputPath) {
        List<Stop> origins = selectOrigins();
        System.err.println("Origins done 1/3 (" + origins.size() + " origins)");
		Map<String, Map<String, Integer>> baseline = computeBaseline(origins);
        System.err.println("Baseline done 2/3 (" + baseline.size() + " stops reachable)");
        List<String> sampledStops = selectSampledStops();
        System.err.println("Sampled " + sampledStops.size() + " stops for scoring");
        Map<String, Double> impactScores = scoreRoutes(origins, baseline, sampledStops);
        System.err.println("Impact scores done 3/3");
        writeCSV(outputPath, impactScores);
        printTop3(impactScores);
        System.err.println("Fully done " + outputPath);
    }

    private List<Stop> selectOrigins() {
        List<Stop> sorted = new ArrayList<>(data.stops.values());
        sorted.sort((a, b) -> {
            int countA = data.stopIndex.getOrDefault(a.stop_id, Collections.emptyList()).size();
            int countB = data.stopIndex.getOrDefault(b.stop_id, Collections.emptyList()).size();
            return Integer.compare(countB, countA);
        });
        return sorted.subList(0, Math.min(NUM_ORIGINS, sorted.size()));
    }

    private List<String> selectSampledStops() {
        List<Stop> allStops = new ArrayList<>(data.stops.values());

        double minLat = allStops.stream().mapToDouble(s -> s.stop_lat).min().orElse(0);
        double maxLat = allStops.stream().mapToDouble(s -> s.stop_lat).max().orElse(0);
        double minLon = allStops.stream().mapToDouble(s -> s.stop_lon).min().orElse(0);
        double maxLon = allStops.stream().mapToDouble(s -> s.stop_lon).max().orElse(0);

        int gridSize = (int) Math.ceil(Math.sqrt(NUM_SAMPLE_STOPS));
        double latStep = (maxLat - minLat) / gridSize;
        double lonStep = (maxLon - minLon) / gridSize;

        Set<String> selected = new LinkedHashSet<>();
        for (int r = 0; r < gridSize && selected.size() < NUM_SAMPLE_STOPS; r++) {
            for (int c = 0; c < gridSize && selected.size() < NUM_SAMPLE_STOPS; c++) {
                double cellLat = minLat + (r + 0.5) * latStep;
                double cellLon = minLon + (c + 0.5) * lonStep;

                Stop closest = null;
                double bestDist = Double.MAX_VALUE;
                for (Stop s : allStops) {
                    double d = Math.pow(s.stop_lat - cellLat, 2) + Math.pow(s.stop_lon - cellLon, 2);
                    if (d < bestDist) { bestDist = d; closest = s; }
                }
                if (closest != null) selected.add(closest.stop_id);
            }
        }

        System.err.println("Grid sampling: " + gridSize + "x" + gridSize + " cells -> " + selected.size() + " unique stops");
        return new ArrayList<>(selected);
    }

	private Map<String, Map<String, Integer>> computeBaseline(List<Stop> origins) {
		Map<String, Map<String, Integer>> baseline = new HashMap<>();
		for (int i = 0; i < origins.size(); i++) {
			Stop origin = origins.get(i);
			System.err.println("  Baseline origin " + (i + 1) + "/" + origins.size() + ": " + origin.stop_name);
			Coordinates coords = new Coordinates(origin.stop_lat, origin.stop_lon);
			Map<String, Integer> result = engine.computeAllRoutes(coords, START_SECONDS, null, null);
			System.err.println("    -> got " + result.size() + " stops");
			baseline.put(origin.stop_id, result);
		}
		return baseline;
	}

	private Map<String, Double> scoreRoutes(List<Stop> origins, Map<String, Map<String, Integer>> baseline, List<String> sampledStops) {
		Map<String, Double> impactScores = new java.util.concurrent.ConcurrentHashMap<>();
		int routeCount = data.routes.size();
		java.util.concurrent.atomic.AtomicInteger routeIdx = new java.util.concurrent.atomic.AtomicInteger(0);

		int threads = Runtime.getRuntime().availableProcessors();
		System.err.println("Using " + threads + " threads");
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);

		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

		for (Map.Entry<String, RouteInfo> routeEntry : data.routes.entrySet()) {
			String routeId = routeEntry.getKey();
			String routeName = routeEntry.getValue().shortName;

			futures.add(pool.submit(() -> {
				int idx = routeIdx.incrementAndGet();
				System.err.println("  Scoring route " + idx + "/" + routeCount + ": " + routeName);
				Set<String> excluded = Set.of(routeId);
				double totalIncrease = 0.0;

				for (Stop origin : origins) {
					Coordinates coords = new Coordinates(origin.stop_lat, origin.stop_lon);
					Map<String, Integer> result = engine.computeAllRoutes(coords, START_SECONDS, null, excluded);
					Map<String, Integer> originBaseline = baseline.get(origin.stop_id);

					for (String stopId : sampledStops) {
						if (!originBaseline.containsKey(stopId)) continue;
						int baseTime = originBaseline.get(stopId);
						int newTime = result.getOrDefault(stopId, baseTime + UNREACHABLE_PENALTY);
						totalIncrease += Math.max(0, newTime - baseTime);
					}
				}

				impactScores.put(routeId, totalIncrease / (origins.size() * sampledStops.size()));
			}));
		}

		for (java.util.concurrent.Future<?> f : futures) {
			try { f.get(); } catch (Exception e) { System.err.println("Thread error: " + e.getMessage()); }
		}

		pool.shutdown();
		return impactScores;
	}

    private void writeCSV(String outputPath, Map<String, Double> impactScores) {
        try (FileWriter fw = new FileWriter(outputPath)) {
            fw.write("routeId,shortName,longName,impactScore\n");
            for (Map.Entry<String, Double> entry : impactScores.entrySet()) {
                String routeId = entry.getKey();
                RouteInfo info = data.routes.get(routeId);
                fw.write(String.format("%s,%s,%s,%.4f\n",
                    routeId,
                    escapeCsv(info.shortName),
                    escapeCsv(info.longName),
                    entry.getValue()));
            }
        } catch (IOException e) {
            System.err.println("Failed to write closure analysis CSV: " + e.getMessage());
        }
    }

    private void printTop3(Map<String, Double> impactScores) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(impactScores.entrySet());
        sorted.sort(Map.Entry.comparingByValue());

        System.err.println("Top 3 routes to close (lowest impact):");
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            String routeId = sorted.get(i).getKey();
            RouteInfo info = data.routes.get(routeId);
            System.err.printf("%d. %s - %s (score: %.4f)%n",
                i + 1, info.shortName, info.longName, sorted.get(i).getValue());
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

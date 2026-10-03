package Window;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TileCache {

	private static final java.util.concurrent.ExecutorService pool = 
		java.util.concurrent.Executors.newFixedThreadPool(6);
    private static final int ZOOM = 12;
    
// 	private static final String OSM = "https://tiles.stadiamaps.com/tiles/osm_bright/";
	private static final String OSM = "https://tile.openstreetmap.org/";
	private static final String UA = "MaastrichtUniProject/1.0 (student project; your@student.maastrichtuniversity.nl)";
    private Path cacheDir;
	private final Set<String> fetching = Collections.synchronizedSet(new HashSet<>());
	private final Map<String, BufferedImage> memCache = new ConcurrentHashMap<>();

    public TileCache() throws IOException {
        cacheDir = Path.of(System.getProperty("user.home"), ".gtfs_tiles", "_default");
        /* cacheDir = Files.createTempDirectory("gtfs_tiles_");
        cacheDir.toFile().deleteOnExit();*/
    }


  public void setCity(String gtfsFilePath) {
        String name = Path.of(gtfsFilePath).getFileName().toString()
            .replaceAll("\\.zip$", "")
            .replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path newDir = Path.of(System.getProperty("user.home"), ".gtfs_tiles", name);
        if (!newDir.equals(cacheDir)) {
            memCache.clear(); 
            cacheDir = newDir;
        }
    }

    private Path ensureCacheDir() {
        try { Files.createDirectories(cacheDir); }
        catch (IOException e) { System.err.println("Cache dir error: " + e.getMessage()); }
        return cacheDir;
    }

    public static int lon2tile(double lon) {
        return (int) Math.floor((lon + 180) / 360 * (1 << ZOOM));
    }

    public static int lat2tile(double lat) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << ZOOM));
    }

    public static double tile2lon(int x) {
        return x / (double)(1 << ZOOM) * 360.0 - 180.0;
    }

	public boolean isFetching() { return !fetching.isEmpty(); }

    public static double tile2lat(int y) {
        double n = Math.PI - 2 * Math.PI * y / (1 << ZOOM);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public static int getZoom() { return ZOOM; }

	public BufferedImage getTile(int x, int y, Runnable onLoaded) {
		String key = x + "_" + y;
		BufferedImage cached = memCache.get(key);
		if (cached != null) return cached;
		Path file = ensureCacheDir().resolve(key + ".png");
		System.err.println("getTile dir=" + cacheDir + " exists=" + Files.exists(file) + " key=" + key);
		if (Files.exists(file)) {
			try {
				BufferedImage img = ImageIO.read(file.toFile());
				if (img != null) memCache.put(key, img);
				return img;
			} catch (IOException e) { return null; }
		}
		if (fetching.contains(key)) return null;
		fetching.add(key);
		pool.submit(() -> {
			try {
				URL url = new URL(OSM + ZOOM + "/" + x + "/" + y + ".png");
				HttpURLConnection c = (HttpURLConnection) url.openConnection();
				c.setRequestProperty("User-Agent", UA);
				c.setConnectTimeout(5000);
				c.setReadTimeout(5000);
				if (c.getResponseCode() != 200) { System.err.println("HTTP " + c.getResponseCode()); return; }
				try (InputStream is = c.getInputStream(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
					byte[] chunk = new byte[4096]; int n;
					while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
					byte[] bytes = buf.toByteArray();
					if (bytes.length == 0) { System.err.println("Empty tile: " + x + "/" + y); return; }
					BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
					if (img != null) {
						memCache.put(key, img);
						ImageIO.write(img, "png", file.toFile());
						if (onLoaded != null) onLoaded.run();
					}
				}
			} catch (Exception e) {
				System.err.println("Tile failed: " + x + "/" + y + " " + e.getMessage());
			} finally {
				fetching.remove(key);
			}
		});
		return null;
	}

	public void prefetch(double minLat, double maxLat, double minLon, double maxLon) {
        int x0 = lon2tile(minLon), x1 = lon2tile(maxLon);
        int y0 = lat2tile(maxLat), y1 = lat2tile(minLat);
        System.err.println("Prefetching " + (x1-x0+1)*(y1-y0+1) + " tiles...");
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                getTile(x, y, null);
    }
}

package Window;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;

public class MapPanel extends JPanel {


	private Map<String, Integer> heatmapData = null;
	private Map<String, Integer> heatmapBaseline = null;
	private Map<String, double[]> heatmapStopCoords = null;
	private Set<String> heatmapValidStops = null;
	private boolean deltaActive = false;
	private int heatmapStartSeconds = 0;
	private int heatmapMaxSeconds = 3600;
	private List<String> closedStations = new ArrayList<>();

	private List<double[][]> analysisLatLons = null;
	private List<Color>     analysisColors  = null;

	private BufferedImage heatmapCache = null;
	private int cachedHeatmapPanelW = -1, cachedHeatmapPanelH = -1;

	private Timer renderTimer;
    private final Supplier<String> fromName;
    private final Supplier<String> toName;
    private Consumer<Location> onLocationClick;
	private TileCache tileCache;
	private List<Map<String, Object>> routeSteps = null;
	private List<int[]>  markerPositions = new ArrayList<>();
	private List<String> markerTooltips  = new ArrayList<>();

    private List<Location> stops = new ArrayList<>();
    private double minLat, maxLat, minLon, maxLon;
    private double minMercY, maxMercY;

    private static double mercatorY(double lat) {
        return Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * (180.0 / Math.PI);
    }

	public TileCache getTileCache() {return tileCache; }

    private int[][] worldPosCache;
    private int cachedPanelW, cachedPanelH;

    private Location routeFrom, routeTo;
    private String routeMode;

    private int offsetX, offsetY;
    private int dragStartX, dragStartY, dragOffX, dragOffY;
    private float zoom = 2.5f;

	private Consumer<double[]> onFreeClick;
	void setOnFreeClick(Consumer<double[]> cb) { this.onFreeClick = cb; }

	private Location heatmapHoveredStop = null;
	private boolean closingModeActive = false;
	private Consumer<Location> onHeatmapStopClick;
	void setOnHeatmapStopClick(Consumer<Location> cb) { this.onHeatmapStopClick = cb; }

	private Consumer<String> onClosedStationRemove;
	void setOnClosedStationRemove(Consumer<String> cb) { this.onClosedStationRemove = cb; }

	public void setClosingMode(boolean active) {
		this.closingModeActive = active;
		if (!active) heatmapHoveredStop = null;
		repaint();
	}

	private boolean snapMode = true;
	void setSnapMode(boolean snap) { this.snapMode = snap; }

	private String loadingMessage = "Please Drag and Drop a GTFS Data file here to get started!";
	public void setLoadingMessage(String msg) { 
		this.loadingMessage = msg; 
		repaint(); 
	}

	private Consumer<File> onDrop;
	void setOnDrop(Consumer<File> cb) { this.onDrop = cb; }

	public void onFileDrop(File f) {
		String fileName = f.getName();
		if (!fileName.endsWith(".zip")) return;
		if (tileCache != null) tileCache.setCity(f.getAbsolutePath());
		if (onDrop != null) { 
			onDrop.accept(f); 
		}
	}


    MapPanel(Supplier<String> fromName, Supplier<String> toName) {
        this.fromName = fromName;
        this.toName   = toName;
        setBackground(Theme.BG);
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        ToolTipManager.sharedInstance().registerComponent(this);
        wireMouseEvents();
		new DropTarget(this, new DropTargetAdapter() {
			@Override
			public void drop(DropTargetDropEvent e) {
				try {
					long now = System.currentTimeMillis();
					e.acceptDrop(DnDConstants.ACTION_COPY);
					List<File> files = (List<File>) e.getTransferable()
						.getTransferData(DataFlavor.javaFileListFlavor);
					if (!files.isEmpty()) onFileDrop(files.get(0));
					System.out.println(System.currentTimeMillis() - now);
				} catch (Exception ex) {
					System.err.println("Drop failed: " + ex.getMessage());
				}
			}
		});
    }

    void setOnLocationClick(Consumer<Location> cb) { this.onLocationClick = cb; }

    void addStop(Location loc) {
        stops.removeIf(s -> s.id().equals(loc.id()));
        stops.add(loc);
        worldPosCache = null;
        repaint();
    }

    void setStops(List<Location> newStops) {
        this.stops = new ArrayList<>(newStops);
        worldPosCache = null;
        if (!stops.isEmpty()) {
            double[] slat = stops.stream().mapToDouble(Location::lat).sorted().toArray();
            double[] slon = stops.stream().mapToDouble(Location::lon).sorted().toArray();
            int n = slat.length;
            double q1lat = slat[n / 4],  q3lat = slat[3 * n / 4];
            double q1lon = slon[n / 4],  q3lon = slon[3 * n / 4];
            double iqrLat = q3lat - q1lat, iqrLon = q3lon - q1lon;
            minLat = Math.max(slat[0],    q1lat - 1.5 * iqrLat);
            maxLat = Math.min(slat[n - 1], q3lat + 1.5 * iqrLat);
            minLon = Math.max(slon[0],    q1lon - 1.5 * iqrLon);
            maxLon = Math.min(slon[n - 1], q3lon + 1.5 * iqrLon);
            minMercY = mercatorY(minLat);
            maxMercY = mercatorY(maxLat);
        }
		SwingUtilities.invokeLater(() -> {
			double centerLat = (minLat + maxLat) / 2;
			double centerLon = (minLon + maxLon) / 2;
			int[] center = worldPos(new Location("", "", centerLat, centerLon));
			offsetX = getWidth() / 2 - (int)(center[0] * zoom);
			offsetY = getHeight() / 2 - (int)(center[1] * zoom);
			repaint();
		});
    }

    private int[][] getWorldPosCache() {
        if (worldPosCache == null || cachedPanelW != getWidth() || cachedPanelH != getHeight()) {
            cachedPanelW = getWidth();
            cachedPanelH = getHeight();
            worldPosCache = new int[stops.size()][2];
            for (int i = 0; i < stops.size(); i++)
                worldPosCache[i] = worldPos(stops.get(i));
        }
        return worldPosCache;
    }

	void setRoute(Location from, Location to, String mode, List<Map<String, Object>> steps) {
		this.routeFrom = from;
		this.routeTo   = to;
		this.routeMode = mode;
		this.routeSteps = steps;
		repaint();
	}


	void setTileCache(TileCache tc) {
		this.tileCache = tc;
		if (renderTimer != null) renderTimer.stop();
		renderTimer = new javax.swing.Timer(33, e -> {
			repaint();
			if (!tc.isFetching()) ((javax.swing.Timer)e.getSource()).stop();
		});
		renderTimer.start();
	}


    void clearRoute() {
        routeFrom = null;
        routeTo   = null;
        routeMode = null;
        markerPositions.clear();
        markerTooltips.clear();
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (closingModeActive) {
            Location hover = findNearestStop(e.getX(), e.getY(), 18);
            if (hover != null) return "Click to close " + hover.name();
        }
        int px = e.getX(), py = e.getY();
        for (int i = 0; i < markerPositions.size(); i++) {
            int[] pos = markerPositions.get(i);
            int dx = px - pos[0], dy = py - pos[1];
            if (dx * dx + dy * dy <= 14 * 14) return markerTooltips.get(i);
        }
        return null;
    }

    private void wireMouseEvents() {
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                dragStartX = e.getX(); dragStartY = e.getY();
                dragOffX = offsetX;    dragOffY = offsetY;
            }
            public void mouseClicked(MouseEvent e) { handleMapClick(e.getX(), e.getY()); }
            public void mouseReleased(MouseEvent e) {
                if (closingModeActive) updateHeatmapHover(e.getX(), e.getY());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
			public void mouseDragged(MouseEvent e) {
				int newOffX = dragOffX + (e.getX() - dragStartX);
				int newOffY = dragOffY + (e.getY() - dragStartY);

				int[] topLeft     = worldPos(new Location("", "", maxLat, minLon));
				int[] bottomRight = worldPos(new Location("", "", minLat, maxLon));

				int mapW = (int)(bottomRight[0] * zoom) - (int)(topLeft[0] * zoom);
				int mapH = (int)(bottomRight[1] * zoom) - (int)(topLeft[1] * zoom);

				int margin = 100;
				offsetX = Math.max(-(int)(topLeft[0] * zoom) - mapW + margin,
						  Math.min(getWidth() - (int)(topLeft[0] * zoom) - margin, newOffX));
				offsetY = Math.max(-(int)(topLeft[1] * zoom) - mapH + margin,
						  Math.min(getHeight() - (int)(topLeft[1] * zoom) - margin, newOffY));

				if (heatmapHoveredStop != null) { heatmapHoveredStop = null; }
				repaint();
			}
			public void mouseMoved(MouseEvent e) {
				if (!closingModeActive) {
					if (heatmapHoveredStop != null) { heatmapHoveredStop = null; repaint(); }
					return;
				}
				updateHeatmapHover(e.getX(), e.getY());
			}
        });
		addMouseWheelListener(e -> {
			float oldZoom = zoom;
			double factor = Math.pow(1.12, -e.getPreciseWheelRotation());
			zoom = Math.max(2f, Math.min(100f, (float)(zoom * factor)));
			float scale = zoom / oldZoom;
			offsetX = (int)(e.getX() - scale * (e.getX() - offsetX));
			offsetY = (int)(e.getY() - scale * (e.getY() - offsetY));
			repaint();
		});
    }

    private void handleMapClick(int px, int py) {

		if (!closedStations.isEmpty() && onClosedStationRemove != null) {
			int padY = 8, lineH = 16, boxW = 190;
			int bx = getWidth() - boxW - 12, by = 12;
			int boxH = padY * 2 + 14 + closedStations.size() * lineH;
			if (px >= bx && px <= bx + boxW && py >= by && py <= by + boxH) {
				int row = (py - by - padY - 10 - lineH) / lineH;
				if (row >= 0 && row < closedStations.size())
					onClosedStationRemove.accept(closedStations.get(row));
				return;
			}
		}

		if (closingModeActive) {
			if (heatmapHoveredStop != null && onHeatmapStopClick != null)
				onHeatmapStopClick.accept(heatmapHoveredStop);
			return;
		}

		if (!snapMode) {
			if (onFreeClick != null) onFreeClick.accept(screenToLatLon(px, py));
			return;
		}

    	if (onLocationClick == null || stops.isEmpty()) return;
        int[][] wpc = getWorldPosCache();
        for (int i = 0; i < stops.size(); i++) {
            int sx = (int)(wpc[i][0] * zoom) + offsetX;
            int sy = (int)(wpc[i][1] * zoom) + offsetY;
            if (Math.abs(px - sx) < 10 && Math.abs(py - sy) < 10) {
                onLocationClick.accept(stops.get(i));
                break;
            }
        }
    }

    private int[] worldPos(Location loc) {
        double lonRange  = maxLon - minLon == 0 ? 1 : maxLon - minLon;
        double mercRange = maxMercY - minMercY == 0 ? 1 : maxMercY - minMercY;

        int pad = 60;
        int usableW = Math.max(getWidth()  - pad * 2, 1);
        int usableH = Math.max(getHeight() - pad * 2, 1);
        double scale = Math.min(usableW / lonRange, usableH / mercRange);

        int projW = (int)(lonRange  * scale);
        int projH = (int)(mercRange * scale);

        double my = mercatorY(loc.lat());
        int x = (int)((loc.lon() - minLon) * scale)   + pad + (usableW - projW) / 2;
        int y = (int)((maxMercY  - my)     * scale)   + pad + (usableH - projH) / 2;
        return new int[]{x, y};
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		if (loadingMessage != null) {
			drawLoadingOverlay(g, loadingMessage);
			return;
		}

        AffineTransform saved = g.getTransform();
        g.translate(offsetX, offsetY);
        g.scale(zoom, zoom);

        if (tileCache != null) drawTiles(g);
        if (analysisLatLons != null) drawAnalysisRoutes(g);
        if (heatmapData != null) drawHeatmap(g);
        if (routeFrom != null && routeTo != null) drawRoute(g);

        g.setTransform(saved);
        drawStops(g);
        drawHeatmapHover(g);
        drawOverlays(g);
    }

	private void drawLoadingOverlay(Graphics2D g, String msg) {
		g.setColor(Theme.BG);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setFont(Theme.bold(16));
		FontMetrics fm = g.getFontMetrics();
		int x = (getWidth() - fm.stringWidth(msg)) / 2;
		int y = getHeight() / 2;
		g.setColor(Theme.TEXT_DARK);
		g.drawString(msg, x, y);
	}

	private void drawTiles(Graphics2D g) {
		double[] tl = screenToLatLon(0, 0);
		double[] br = screenToLatLon(getWidth(), getHeight());
		double visMaxLat = Math.min(tl[0], maxLat);
		double visMinLat = Math.max(br[0], minLat);
		double visMinLon = Math.max(tl[1], minLon);
		double visMaxLon = Math.min(br[1], maxLon);
		if (visMaxLat <= visMinLat || visMaxLon <= visMinLon) return;

		int x0 = TileCache.lon2tile(visMinLon) - 1;
		int x1 = TileCache.lon2tile(visMaxLon) + 1;
		int y0 = TileCache.lat2tile(visMaxLat) - 1;
		int y1 = TileCache.lat2tile(visMinLat) + 1;

		for (int tx = x0; tx <= x1; tx++) {
			for (int ty = y0; ty <= y1; ty++) {
				BufferedImage img = tileCache.getTile(tx, ty, () -> SwingUtilities.invokeLater(this::repaint));
				if (img == null) continue;

				double tileLat = TileCache.tile2lat(ty);
				double tileLon = TileCache.tile2lon(tx);
				double tileLat2 = TileCache.tile2lat(ty + 1);
				double tileLon2 = TileCache.tile2lon(tx + 1);
				int[] topLeft = worldPos(new Location(tx+"_"+ty+"tl", "", tileLat,  tileLon));
				int[] bottomRight = worldPos(new Location(tx+"_"+ty+"br", "", tileLat2, tileLon2));

				int w = bottomRight[0] - topLeft[0];
				int h = bottomRight[1] - topLeft[1];
				if (w <= 0 || h <= 0) continue;

				g.drawImage(img, topLeft[0], topLeft[1], w, h, null);
			}
		}
}

    private void drawEmptyState(Graphics2D g) {
        g.setColor(Theme.BG);
        g.fillRect(0, 0, getWidth(), getHeight());

        int step = 40;
        g.setColor(new Color(0, 0, 0, 12));
        g.setStroke(new BasicStroke(1));
        for (int x = 0; x < getWidth(); x += step)  g.drawLine(x, 0, x, getHeight());
        for (int y = 0; y < getHeight(); y += step)  g.drawLine(0, y, getWidth(), y);

        String line1 = "No GTFS data loaded";
        String line2 = "Load a feed via the routing engine to render stops";

        g.setFont(Theme.bold(18));
        FontMetrics fm1 = g.getFontMetrics();
        int x1 = (getWidth() - fm1.stringWidth(line1)) / 2;
        int y1 = getHeight() / 2 - 12;

        g.setFont(Theme.regular(13));
        FontMetrics fm2 = g.getFontMetrics();
        int x2 = (getWidth() - fm2.stringWidth(line2)) / 2;
        int y2 = y1 + 28;

        g.setColor(new Color(255, 255, 255, 200));
        int boxX = Math.min(x1, x2) - 24;
        int boxY = y1 - fm1.getAscent() - 14;
        int boxW = Math.max(fm1.stringWidth(line1), fm2.stringWidth(line2)) + 48;
        int boxH = 70;
        g.fillRoundRect(boxX, boxY, boxW, boxH, 14, 14);
        g.setColor(Theme.DIVIDER);
        g.setStroke(new BasicStroke(1));
        g.drawRoundRect(boxX, boxY, boxW, boxH, 14, 14);

        g.setFont(Theme.bold(18));
        g.setColor(Theme.TEXT_DARK);
        g.drawString(line1, x1, y1);

        g.setFont(Theme.regular(13));
        g.setColor(Theme.TEXT_MID);
        g.drawString(line2, x2, y2);
    }

    private void drawStops(Graphics2D g) {
        if (stops.isEmpty()) return;
        String fn = fromName.get();
        String tn = toName.get();
        int[][] wpc = getWorldPosCache();
        int W = getWidth(), H = getHeight();

        int cell = 8;
        Set<Long> occupied = new HashSet<>(512);

        for (int i = 0; i < stops.size(); i++) {
            Location loc = stops.get(i);
            boolean isFrom = loc.name().equals(fn);
            boolean isTo   = loc.name().equals(tn);

            if (!isFrom && !isTo && zoom < 2f) continue;

            int sx = (int)(wpc[i][0] * zoom) + offsetX;
            int sy = (int)(wpc[i][1] * zoom) + offsetY;
            if (sx < -20 || sx > W + 20 || sy < -20 || sy > H + 20) continue;

            if (!isFrom && !isTo) {
                long key = ((long)(sx / cell)) << 32 | ((sy / cell) & 0xFFFFFFFFL);
                if (!occupied.add(key)) continue;
            }

            if (isFrom) drawSelectedPin(g, loc, sx, sy, Theme.ACCENT_GREEN);
            else if (isTo) drawSelectedPin(g, loc, sx, sy, Theme.ACCENT_RED);
            else if (heatmapData != null || analysisLatLons != null) continue;
        }
    }

    private void drawAnalysisRoutes(Graphics2D g) {
        int[] tl = worldPos(new Location("", "", maxLat, minLon));
        int[] br = worldPos(new Location("", "", minLat, maxLon));
        Shape savedClip = g.getClip();
        g.setClip(tl[0], tl[1], br[0] - tl[0], br[1] - tl[1]);

        float strokeW = 3f / zoom;
        for (int i = 0; i < analysisLatLons.size(); i++) {
            double[][] pts = analysisLatLons.get(i);
            Color c = analysisColors.get(i);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 170));
            g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
            boolean started = false;
            for (double[] pt : pts) {
                if (pt[0] < minLat || pt[0] > maxLat || pt[1] < minLon || pt[1] > maxLon) {
                    started = false;
                    continue;
                }
                int[] wp = worldPos(new Location("", "", pt[0], pt[1]));
                if (!started) { path.moveTo(wp[0], wp[1]); started = true; }
                else          { path.lineTo(wp[0], wp[1]); }
            }
            g.draw(path);
        }

        g.setClip(savedClip);
    }

    private void drawSelectedPin(Graphics2D g, Location loc, int sx, int sy, Color color) {
        int r = 8;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
        g.fillOval(sx - r - 5, sy - r - 5, (r + 5) * 2, (r + 5) * 2);
        g.setColor(Color.WHITE);
        g.fillOval(sx - r - 1, sy - r - 1, (r + 1) * 2, (r + 1) * 2);
        g.setColor(color);
        g.fillOval(sx - r, sy - r, r * 2, r * 2);

        g.setFont(Theme.bold(11));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(loc.name());
        int tx = sx - tw / 2;
        int ty = sy - 14;
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRoundRect(tx - 4, ty - 12, tw + 8, 15, 5, 5);
        g.setColor(Theme.TEXT_DARK);
        g.drawString(loc.name(), tx, ty);
    }

    private void drawIdlePin(Graphics2D g, int sx, int sy) {
        int r = 3;
        g.setColor(new Color(200, 200, 200, 150));
        g.fillOval(sx - r - 1, sy - r - 1, (r + 1) * 2, (r + 1) * 2);
        g.setColor(new Color(0x94A3B8));
        g.fillOval(sx - r, sy - r, r * 2, r * 2);
    }


	private void drawRoute(Graphics2D g) {
		if (routeSteps == null || routeSteps.isEmpty()) return;

		double prevLat = routeFrom.lat();
		double prevLon = routeFrom.lon();
		for (Map<String, Object> step : routeSteps) {
			String mode = (String) step.get("mode");
			Map<?, ?> toPoint = (Map<?, ?>) step.get("to");
			if (toPoint == null) continue;
			double lat = ((Number) toPoint.get("lat")).doubleValue();
			double lon = ((Number) toPoint.get("lon")).doubleValue();
			if (!"walk".equals(mode)) {
				List<Map<?, ?>> waypoints = (List<Map<?, ?>>) step.get("waypoints");
				float w = 4f / zoom;
				double segLat = prevLat, segLon = prevLon;
				List<?> pts = (waypoints != null && !waypoints.isEmpty()) ? waypoints : List.of(toPoint);
				for (Object wp : pts) {
					Map<?, ?> p = (Map<?, ?>) wp;
					double wLat = ((Number) p.get("lat")).doubleValue();
					double wLon = ((Number) p.get("lon")).doubleValue();
					int[] from = worldPos(new Location("", "", segLat, segLon));
					int[] to   = worldPos(new Location("", "", wLat, wLon));
					g.setColor(new Color(Theme.ROUTE_BUS.getRed(), Theme.ROUTE_BUS.getGreen(), Theme.ROUTE_BUS.getBlue(), 40));
					g.setStroke(new BasicStroke(w * 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.drawLine(from[0], from[1], to[0], to[1]);
					g.setColor(Theme.ROUTE_BUS);
					g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					g.drawLine(from[0], from[1], to[0], to[1]);
					segLat = wLat;
					segLon = wLon;
				}
			}
			prevLat = lat;
			prevLon = lon;
		}

		for (Map<String, Object> step : routeSteps) {
			if ("walk".equals(step.get("mode"))) continue;
			List<Map<?, ?>> waypoints = (List<Map<?, ?>>) step.get("waypoints");
			if (waypoints == null) continue;
			float r = 3f / zoom;
			for (Map<?, ?> wp : waypoints) {
				double wLat = ((Number) wp.get("lat")).doubleValue();
				double wLon = ((Number) wp.get("lon")).doubleValue();
				int[] pos = worldPos(new Location("", "", wLat, wLon));
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(1.2f / zoom));
				g.fill(new Ellipse2D.Float(pos[0] - r, pos[1] - r, r * 2, r * 2));
				g.setColor(Theme.ROUTE_BUS);
				g.draw(new Ellipse2D.Float(pos[0] - r, pos[1] - r, r * 2, r * 2));
			}
		}

		prevLat = routeFrom.lat();
		prevLon = routeFrom.lon();
		for (Map<String, Object> step : routeSteps) {
			String mode = (String) step.get("mode");
			Map<?, ?> toPoint = (Map<?, ?>) step.get("to");
			if (toPoint == null) continue;
			double lat = ((Number) toPoint.get("lat")).doubleValue();
			double lon = ((Number) toPoint.get("lon")).doubleValue();
			if ("walk".equals(mode)) {
				int[] from = worldPos(new Location("", "", prevLat, prevLon));
				int[] to   = worldPos(new Location("", "", lat, lon));
				float w = 4f / zoom;
				g.setColor(new Color(Theme.ROUTE_WALK.getRed(), Theme.ROUTE_WALK.getGreen(), Theme.ROUTE_WALK.getBlue(), 40));
				g.setStroke(new BasicStroke(w * 2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(from[0], from[1], to[0], to[1]);
				g.setColor(Theme.ROUTE_WALK);
				g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
					10, new float[]{8f / zoom, 6f / zoom}, 0));
				g.drawLine(from[0], from[1], to[0], to[1]);
			}
			prevLat = lat;
			prevLon = lon;
		}
	}

    private Stroke routeStroke(String mode) {
        if ("walk".equals(mode)) {
            return new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{8f, 6f}, 0);
        }
        return new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private Color routeColor(String mode) {
        return switch (mode) {
            case "bus"  -> Theme.ROUTE_BUS;
            case "walk" -> Theme.ROUTE_WALK;
            case "bike" -> Theme.ROUTE_BIKE;
            default     -> Theme.ACCENT;
        };
    }

    private void drawArrow(Graphics2D g, int x1, int y1, int x2, int y2, Color c) {
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int len = 12;
        int[] xp = {x2, (int)(x2 - len * Math.cos(angle - 0.4)), (int)(x2 - len * Math.cos(angle + 0.4))};
        int[] yp = {y2, (int)(y2 - len * Math.sin(angle - 0.4)), (int)(y2 - len * Math.sin(angle + 0.4))};
        g.setStroke(new BasicStroke(1));
        g.setColor(c);
        g.fillPolygon(xp, yp, 3);
    }

    private void drawTransferMarkers(Graphics2D g) {
        if (routeSteps == null || routeSteps.size() < 2) return;
        markerPositions.clear();
        markerTooltips.clear();

        for (int i = 0; i < routeSteps.size() - 1; i++) {
            Map<String, Object> step     = routeSteps.get(i);
            Map<String, Object> nextStep = routeSteps.get(i + 1);
            String mode     = (String) step.get("mode");
            String nextMode = (String) nextStep.get("mode");

            boolean isBoarding  = "walk".equals(mode) && "ride".equals(nextMode);
            boolean isTransfer  = "ride".equals(mode) && "ride".equals(nextMode);
            if (!isBoarding && !isTransfer) continue;

            Map<?, ?> toPoint = (Map<?, ?>) step.get("to");
            if (toPoint == null) continue;
            double lat = ((Number) toPoint.get("lat")).doubleValue();
            double lon = ((Number) toPoint.get("lon")).doubleValue();

            int[] wp = worldPos(new Location("", "", lat, lon));
            int sx = (int)(wp[0] * zoom) + offsetX;
            int sy = (int)(wp[1] * zoom) + offsetY;

            Color fill  = isTransfer ? new Color(0xF97316) : new Color(0x0D9488);
            Color ring  = fill.darker();

            int r = 7;
            g.setColor(Color.WHITE);
            g.fillOval(sx - r - 2, sy - r - 2, (r + 2) * 2, (r + 2) * 2);
            g.setColor(fill);
            g.fillOval(sx - r, sy - r, r * 2, r * 2);
            g.setColor(ring);
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(sx - r, sy - r, r * 2, r * 2);

            @SuppressWarnings("unchecked")
            Map<String, String> route = (Map<String, String>) nextStep.get("route");
            String shortName = route != null ? route.get("shortName") : "?";
            String headSign  = route != null ? route.get("headSign")  : "";
            String boardTime = (String) nextStep.get("startTime");

            String tooltip;
            if (isTransfer) {
                String stopName = (String) step.get("stop");
                tooltip = "<html><b>" + stopName + "</b><br>"
                        + "Transfer to <b>" + shortName + "</b> → " + headSign + "<br>"
                        + "Departs: " + boardTime + "</html>";
            } else {
                tooltip = "<html>Board <b>" + shortName + "</b> → " + headSign + "<br>"
                        + "Departs: " + boardTime + "</html>";
            }

            markerPositions.add(new int[]{sx, sy});
            markerTooltips.add(tooltip);
        }
    }

    private void drawOverlays(Graphics2D g) {
        drawTransferMarkers(g);
        drawLegend(g);
        drawClosedStations(g);
        drawZoomHint(g);
    }

    private void drawClosedStations(Graphics2D g) {
        if (closedStations.isEmpty()) return;
        int padX = 10, padY = 8, lineH = 16;
        int boxW = 190;
        int boxH = padY * 2 + 14 + closedStations.size() * lineH;
        int x = getWidth() - boxW - 12;
        int y = 12;

        g.setColor(new Color(255, 255, 255, 220));
        g.fillRoundRect(x, y, boxW, boxH, 10, 10);
        g.setColor(Theme.DIVIDER);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRoundRect(x, y, boxW, boxH, 10, 10);

        g.setFont(Theme.bold(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Closed stations", x + padX, y + padY + 10);

        g.setFont(Theme.regular(11));
        for (int i = 0; i < closedStations.size(); i++) {
            int ly = y + padY + 10 + (i + 1) * lineH;
            g.setColor(new Color(210, 45, 45));
            g.drawString("×", x + padX, ly);
            g.setColor(Theme.TEXT_MID);
            String name = closedStations.get(i);
            if (name.length() > 22) name = name.substring(0, 20) + "…";
            g.drawString(name, x + padX + 14, ly);
        }
    }

    private void drawAnalysisLegend(Graphics2D g) {
        int x = 16, boxY = getHeight() - 110, boxW = 168, boxH = 98;
        g.setColor(new Color(255, 255, 255, 215));
        g.fillRoundRect(x - 8, boxY, boxW, boxH, 10, 10);
        g.setColor(Theme.DIVIDER);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRoundRect(x - 8, boxY, boxW, boxH, 10, 10);
        int y = boxY + 18;
        g.setFont(Theme.bold(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Closure Impact", x, y);
        Color[] tiers  = {new Color(220, 38, 38), new Color(249, 115, 22), new Color(234, 179, 8), new Color(34, 197, 94), new Color(148, 163, 184)};
        String[] labels = {"High (worst to close)", "Medium-high", "Medium-low", "Low (best to close)", "Zero impact"};
        for (int i = 0; i < tiers.length; i++) {
            int ly = y + 14 + i * 15;
            g.setColor(tiers[i]);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x, ly - 4, x + 22, ly - 4);
            g.setFont(Theme.regular(10));
            g.setColor(Theme.TEXT_DARK);
            g.drawString(labels[i], x + 28, ly);
        }
    }

    private void drawLegend(Graphics2D g) {
        if (analysisLatLons != null) { drawAnalysisLegend(g); return; }
        boolean showHeatmap = heatmapData != null;
        int lineH = 18;
        int heatmapExtra = showHeatmap ? 46 : 0;
        int boxH = 14 + 5 * lineH + 22 + heatmapExtra;
        int boxW = 160;
        int x = 16;
        int boxY = getHeight() - boxH - 12;

        g.setColor(new Color(255, 255, 255, 215));
        g.fillRoundRect(x - 8, boxY, boxW, boxH, 10, 10);
        g.setColor(Theme.DIVIDER);
        g.setStroke(new BasicStroke(0.8f));
        g.drawRoundRect(x - 8, boxY, boxW, boxH, 10, 10);

        int y = boxY + 20;
        g.setFont(Theme.bold(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Legend", x, y);

        String[] labels  = {"Bus route", "Walking route"};
        Color[]  colors  = {Theme.ROUTE_BUS, Theme.ROUTE_WALK};
        boolean[] dashed = {false, true};
        for (int i = 0; i < labels.length; i++) {
            int ly = y + lineH + i * lineH;
            g.setColor(colors[i]);
            g.setStroke(dashed[i]
                ? new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{6f, 4f}, 0)
                : new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x, ly - 3, x + 28, ly - 3);
            g.setStroke(new BasicStroke(1));
            g.setFont(Theme.regular(11));
            g.setColor(Theme.TEXT_DARK);
            g.drawString(labels[i], x + 34, ly);
        }

        int stopY = y + 3 * lineH;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(1.5f));
        g.fillOval(x + 9, stopY - 7, 8, 8);
        g.setColor(Theme.ROUTE_BUS);
        g.drawOval(x + 9, stopY - 7, 8, 8);
        g.setFont(Theme.regular(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Bus stop", x + 34, stopY);

        int boardY = y + 4 * lineH;
        Color boardFill = new Color(0x0D9488);
        g.setColor(Color.WHITE);
        g.fillOval(x + 8, boardY - 8, 10, 10);
        g.setColor(boardFill);
        g.fillOval(x + 9, boardY - 7, 8, 8);
        g.setColor(boardFill.darker());
        g.drawOval(x + 9, boardY - 7, 8, 8);
        g.setFont(Theme.regular(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Board", x + 34, boardY);

        int transferY = y + 5 * lineH;
        Color transferFill = new Color(0xF97316);
        g.setColor(Color.WHITE);
        g.fillOval(x + 8, transferY - 8, 10, 10);
        g.setColor(transferFill);
        g.fillOval(x + 9, transferY - 7, 8, 8);
        g.setColor(transferFill.darker());
        g.drawOval(x + 9, transferY - 7, 8, 8);
        g.setFont(Theme.regular(11));
        g.setColor(Theme.TEXT_DARK);
        g.drawString("Transfer", x + 34, transferY);

        if (showHeatmap) {
            int barLabelY = transferY + lineH + 4;
            g.setFont(Theme.bold(11));
            g.setColor(Theme.TEXT_DARK);
            g.drawString(deltaActive ? "Time impact" : "Journey time", x, barLabelY);
            int barX = x, barTop = barLabelY + 5, barW = 136, barH = 9;
            for (int i = 0; i < barW; i++) {
                float r = (float) i / barW;
                g.setColor(deltaActive ? heatmapDeltaColor(r) : heatmapColor(r));
                g.fillRect(barX + i, barTop, 1, barH);
            }
            g.setColor(Theme.DIVIDER);
            g.setStroke(new BasicStroke(0.5f));
            g.drawRect(barX, barTop, barW, barH);
            g.setFont(Theme.regular(10));
            g.setColor(Theme.TEXT_LIGHT);
            String lo = deltaActive ? "None" : "Short";
            String hi = deltaActive ? "High" : "Long";
            g.drawString(lo, barX, barTop + barH + 11);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(hi, barX + barW - fm.stringWidth(hi), barTop + barH + 11);
        }
    }

    private void drawZoomHint(Graphics2D g) {
        g.setFont(Theme.regular(10));
        g.setColor(Theme.TEXT_LIGHT);
        String hint = String.format("%.1fx  ·  scroll to zoom  ·  drag to pan", zoom);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(hint, getWidth() - fm.stringWidth(hint) - 12, getHeight() - 10);
    }

	private double[] screenToLatLon(int sx, int sy) {
		double wx = (sx - offsetX) / zoom;
		double wy = (sy - offsetY) / zoom;

		double lonRange  = maxLon - minLon == 0 ? 1 : maxLon - minLon;
		double mercRange = maxMercY - minMercY == 0 ? 1 : maxMercY - minMercY;
		int pad = 60;
		int usableW = Math.max(getWidth()  - pad * 2, 1);
		int usableH = Math.max(getHeight() - pad * 2, 1);
		double scale = Math.min(usableW / lonRange, usableH / mercRange);
		int projW = (int)(lonRange  * scale);
		int projH = (int)(mercRange * scale);

		double lon = (wx - pad - (usableW - projW) / 2.0) / scale + minLon;
		double my  = maxMercY - (wy - pad - (usableH - projH) / 2.0) / scale;
		double lat = Math.toDegrees(2 * Math.atan(Math.exp(my * Math.PI / 180.0)) - Math.PI / 2);
		return new double[]{lat, lon};
	}

	public void setHeatmap(Map<String, Integer> data, int startSeconds, int maxSeconds) {
		this.heatmapData = data;
		this.heatmapStartSeconds = startSeconds;
		this.heatmapMaxSeconds = maxSeconds;
		this.heatmapCache = null;
		repaint();
	}

	public void setHeatmapBaseline(Map<String, Integer> baseline) {
		this.heatmapBaseline = baseline;
		this.deltaActive = false;
		this.heatmapCache = null;
	}

	public void setHeatmapStopCoords(Map<String, double[]> coords) {
		this.heatmapStopCoords = coords;
	}

	public void setHeatmapValidStops(Set<String> ids) {
		this.heatmapValidStops = ids;
	}

	public void activateDelta() {
		this.deltaActive = true;
		this.heatmapCache = null;
	}

	public void clearHeatmap() {
		this.heatmapData = null;
		this.heatmapBaseline = null;
		this.heatmapStopCoords = null;
		this.heatmapValidStops = null;
		this.deltaActive = false;
		this.heatmapCache = null;
		this.heatmapHoveredStop = null;
		repaint();
	}

	public void setClosedStations(List<String> names) {
		this.closedStations = new ArrayList<>(names);
		repaint();
	}

	public void setAnalysisRoutes(List<double[][]> latLons, List<Color> colors) {
		this.analysisLatLons = latLons;
		this.analysisColors  = colors;
		repaint();
	}

	public void clearAnalysisRoutes() {
		this.analysisLatLons = null;
		this.analysisColors  = null;
		repaint();
	}

	private void updateHeatmapHover(int px, int py) {
		Location prev = heatmapHoveredStop;
		heatmapHoveredStop = findNearestStop(px, py, 18);
		if (heatmapHoveredStop != prev) repaint();
	}

	private Location findNearestStop(int px, int py, int threshold) {
		if (stops.isEmpty()) return null;
		int[][] wpc = getWorldPosCache();
		Location best = null;
		int bestD2 = threshold * threshold;
		for (int i = 0; i < stops.size(); i++) {
			if (!closingModeActive && heatmapValidStops != null && !heatmapValidStops.contains(stops.get(i).name())) continue;
			int sx = (int)(wpc[i][0] * zoom) + offsetX;
			int sy = (int)(wpc[i][1] * zoom) + offsetY;
			int dx = px - sx, dy = py - sy;
			int d2 = dx * dx + dy * dy;
			if (d2 < bestD2) { bestD2 = d2; best = stops.get(i); }
		}
		return best;
	}

	private void drawHeatmapHover(Graphics2D g) {
		if (heatmapHoveredStop == null || !closingModeActive) return;
		int[] wp = worldPos(heatmapHoveredStop);
		int sx = (int)(wp[0] * zoom) + offsetX;
		int sy = (int)(wp[1] * zoom) + offsetY;

		g.setColor(new Color(220, 50, 50, 45));
		g.fillOval(sx - 18, sy - 18, 36, 36);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.5f));
		g.fillOval(sx - 10, sy - 10, 20, 20);
		g.setColor(new Color(210, 45, 45));
		g.fillOval(sx - 9, sy - 9, 18, 18);
		g.setColor(Color.WHITE);
		g.setFont(Theme.bold(11));
		FontMetrics fm = g.getFontMetrics();
		String cross = "×";
		g.drawString(cross, sx - fm.stringWidth(cross) / 2, sy + fm.getAscent() / 2 - 1);
	}

	private void drawHeatmap(Graphics2D g) {
		if (heatmapData == null || stops.isEmpty()) return;
		int W = getWidth(), H = getHeight();
		if (heatmapCache == null || cachedHeatmapPanelW != W || cachedHeatmapPanelH != H) {
			heatmapCache = buildHeatmapImage(W, H);
			cachedHeatmapPanelW = W;
			cachedHeatmapPanelH = H;
		}
		Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		Composite orig = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.72f));
		g.drawImage(heatmapCache, 0, 0, W, H, null);
		g.setComposite(orig);
		if (prevInterp != null)
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
	}

	private BufferedImage buildHeatmapImage(int W, int H) {
		if (heatmapStopCoords == null) return new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		float[] accVal = new float[W * H];
		float[] accWt  = new float[W * H];
		boolean delta = deltaActive && heatmapBaseline != null;

		int radius = 42;
		float sigma = radius / 2.5f;
		float sig2  = 2f * sigma * sigma;
		int lutSide = radius + 1;
		float[] gLUT = new float[lutSide * lutSide];
		float r2 = (float) radius * radius;
		for (int dy = 0; dy <= radius; dy++) {
			float dy2 = dy * dy;
			for (int dx = 0; dx <= radius; dx++) {
				float d2 = dx * dx + dy2;
				gLUT[dy * lutSide + dx] = (d2 > r2) ? 0f : (float) Math.exp(-d2 / sig2);
			}
		}

		float journeyScale = Math.max(1800f, heatmapMaxSeconds);
		float deltaScale = 120f;
		if (delta) {
			for (Map.Entry<String, Integer> base : heatmapBaseline.entrySet()) {
				Integer after = heatmapData.get(base.getKey());
				if (after != null) deltaScale = Math.max(deltaScale, (float)(after - base.getValue()));
			}
		}

		if (delta) {
			for (Map.Entry<String, Integer> base : heatmapBaseline.entrySet()) {
				double[] coords = heatmapStopCoords.get(base.getKey());
				if (coords == null) continue;
				Integer after = heatmapData.get(base.getKey());
				float v = (after == null) ? 1f : Math.min(1f, Math.max(0f, (float)(after - base.getValue()) / deltaScale));
				int[] wp = worldPos(new Location("", "", coords[0], coords[1]));
				int cx = wp[0], cy = wp[1];
				int x0 = Math.max(0, cx - radius), x1 = Math.min(W - 1, cx + radius);
				int y0 = Math.max(0, cy - radius), y1 = Math.min(H - 1, cy + radius);
				for (int py = y0; py <= y1; py++) {
					int dy = Math.abs(py - cy);
					int lutY = dy * lutSide;
					int rowBase = py * W;
					for (int px = x0; px <= x1; px++) {
						float w = gLUT[lutY + Math.abs(px - cx)];
						if (w == 0f) continue;
						int idx = rowBase + px;
						accVal[idx] += v * w;
						accWt[idx]  += w;
					}
				}
			}
		} else {
			for (Map.Entry<String, Integer> entry : heatmapData.entrySet()) {
				double[] coords = heatmapStopCoords.get(entry.getKey());
				if (coords == null) continue;
				float v = Math.min(1f, Math.max(0f, (float)(entry.getValue() - heatmapStartSeconds) / journeyScale));
				int[] wp = worldPos(new Location("", "", coords[0], coords[1]));
				int cx = wp[0], cy = wp[1];
				int x0 = Math.max(0, cx - radius), x1 = Math.min(W - 1, cx + radius);
				int y0 = Math.max(0, cy - radius), y1 = Math.min(H - 1, cy + radius);
				for (int py = y0; py <= y1; py++) {
					int dy = Math.abs(py - cy);
					int lutY = dy * lutSide;
					int rowBase = py * W;
					for (int px = x0; px <= x1; px++) {
						float w = gLUT[lutY + Math.abs(px - cx)];
						if (w == 0f) continue;
						int idx = rowBase + px;
						accVal[idx] += v * w;
						accWt[idx]  += w;
					}
				}
			}
		}

		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
		for (int idx = 0; idx < W * H; idx++) {
			float wt = accWt[idx];
			if (wt < 0.01f) continue;
			float v = accVal[idx] / wt;
			int alpha = Math.min(255, (int)(wt * 255f));
			Color c = delta ? heatmapDeltaColor(v) : heatmapColor(v);
			pixels[idx] = (alpha << 24) | (c.getRGB() & 0x00FFFFFF);
		}
		return img;
	}

	private Color heatmapColor(float r) {
		r = Math.min(1f, Math.max(0f, r));
		if (r < 0.33f) {
			float t = r / 0.33f;
			return new Color((int)(20 + 235 * t), (int)(180 + 50 * t), (int)(60 * (1 - t)));
		} else if (r < 0.67f) {
			float t = (r - 0.33f) / 0.34f;
			return new Color(255, (int)(230 - 100 * t), 0);
		} else {
			float t = (r - 0.67f) / 0.33f;
			return new Color((int)(255 - 40 * t), (int)(130 - 100 * t), 0);
		}
	}

	private Color heatmapDeltaColor(float r) {
		r = Math.min(1f, Math.max(0f, r));
		if (r < 0.05f) return new Color(60, 180, 60);
		if (r < 0.33f) {
			float t = (r - 0.05f) / 0.28f;
			return new Color((int)(60 + 195 * t), (int)(180 - 30 * t), 0);
		} else if (r < 0.67f) {
			float t = (r - 0.33f) / 0.34f;
			return new Color(255, (int)(150 - 110 * t), 0);
		} else {
			float t = (r - 0.67f) / 0.33f;
			return new Color((int)(255 - 55 * t), (int)(40 * (1 - t)), 0);
		}
	}
}

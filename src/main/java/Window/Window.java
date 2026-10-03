package Window;

import routing.RoutingEngine;
import util.RequestValidator;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

import GTFS.GTFSParser;
import GTFS.Stop;
import GTFS.Trip;
import GTFS.StopTime;
import Structures.Coordinates;
import GTFS.ClosureAnalysis;
import GTFS.GTFSData;

import javax.swing.*;
import javax.swing.border.*;

import GTFS.GTFSData;
import GTFS.GTFSParser;

public class Window extends JFrame {
	private HeatmapButton heatmapBtn = new HeatmapButton();
	private CloseStopsButton closeStopsBtn = new CloseStopsButton();
	private final java.util.Set<String> heatmapExcluded = new java.util.HashSet<>();
	private final java.util.List<String> closedStationNames = new java.util.ArrayList<>();
	private int baselineStartSeconds = -1;
	private RequestValidator validator = new RequestValidator();
	private JTextField fromField, toField;
    private JToggleButton btnBus, btnWalk, btnBike;
    private MapPanel mapPanel;
    private JPanel resultsPanel;
    private String selMode = "bus";
	private GTFSParser parser = new GTFSParser();
    private List<Location> loadedStops = new ArrayList<>();
	private GTFSData currentData;
	private String clickTarget = "from";
	private JToggleButton fromLabel, toLabel;

	private record AnalysisEntry(String routeId, String shortName, String longName, double score) {}
	private List<AnalysisEntry> analysisEntries = null;
	private JToggleButton viewAnalysisBtn;
	private JButton filterBtn;
	private JCheckBoxMenuItem cbHigh, cbMedHigh, cbMedLow, cbLow, cbZero;
	private JCheckBoxMenuItem cbBus, cbMetroTram, cbRail, cbOther;

	public MapPanel getMapPanel() { return mapPanel; }

    public void init(String title, int width, int height, Color ignored) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored2) {}

        setTitle(title);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(width, height);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

		fromField = styledSearchField("Search origin...");
		toField   = styledSearchField("Search destination...");

		wireAutocomplete(fromField, true);
		wireAutocomplete(toField, false);

		mapPanel = new MapPanel(() -> fromField.getText(),
								() -> toField.getText());

			
		mapPanel.setOnDrop(file -> new Thread(() -> {
			try {
				SwingUtilities.invokeLater(() -> {
					fromField.setEnabled(false);
					toField.setEnabled(false);
					fromField.setText("Loading...");
					toField.setText("Loading...");
					mapPanel.setLoadingMessage("Loading " + file.getName() + "...");
				});


				SwingUtilities.invokeLater(() -> mapPanel.setLoadingMessage("Parsing GTFS data"));
				GTFSData data = parser.parse(file.getAbsolutePath());

				currentData = data;

				Map<String, Location> byName = new LinkedHashMap<>();
				for (var s : data.stops.values())
					byName.putIfAbsent(s.stop_name, new Location(s.stop_id, s.stop_name, s.stop_lat, s.stop_lon));
				List<Location> stops = new ArrayList<>(byName.values());

				SwingUtilities.invokeLater(() -> {
					mapPanel.setLoadingMessage(null);
					loadStops(stops);
					mapPanel.repaint();
				});

				TileCache tc = new TileCache();
				tc.setCity(file.getAbsolutePath());
				SwingUtilities.invokeLater(() -> {
					mapPanel.setTileCache(tc);
					mapPanel.repaint();
				});

			} catch (Exception ex) {
				SwingUtilities.invokeLater(() -> {
					mapPanel.setLoadingMessage("Please Drag and Drop a GTFS Data file here to get started!");
					fromField.setEnabled(false);
					toField.setEnabled(false);
					fromField.setText("Search origin...");
					fromField.setForeground(Theme.TEXT_LIGHT);
					toField.setText("Search destination...");
					toField.setForeground(Theme.TEXT_LIGHT);
					JOptionPane.showMessageDialog(Window.this, ex.getMessage(), "Load error", JOptionPane.ERROR_MESSAGE);
				});
			}
		}).start());

		mapPanel.setOnLocationClick(loc -> {
			if (clickTarget.equals("from")) {
				fromField.setText(loc.name());
				fromField.setForeground(Theme.TEXT_DARK);
			} else {
				toField.setText(loc.name());
				toField.setForeground(Theme.TEXT_DARK);
			}
			mapPanel.repaint();
		});


		mapPanel.setOnFreeClick(latlon -> {
			String name = String.format("%.4f, %.4f", latlon[0], latlon[1]);
			String id = clickTarget.equals("from") ? "custom_from" : "custom_to";
			Location loc = new Location(id, name, latlon[0], latlon[1]);
			loadedStops.removeIf(l -> l.id().equals(id));
			loadedStops.add(loc);
			if (clickTarget.equals("from")) {
				fromField.setText(name);
				fromField.setForeground(Theme.TEXT_DARK);
			} else {
				toField.setText(name);
				toField.setForeground(Theme.TEXT_DARK);
			}
			mapPanel.addStop(loc);
		});

        resetDropdowns();

		JPanel mapWrapper = new JPanel(null) {
			@Override public boolean isOptimizedDrawingEnabled() { return false; }
		};
		mapWrapper.setLayout(new OverlayLayout(mapWrapper));
		mapPanel.setAlignmentX(0.5f);
		mapPanel.setAlignmentY(0.5f);

		JPanel btnOverlay = new JPanel(new BorderLayout());
		btnOverlay.setOpaque(false);
		btnOverlay.setAlignmentX(0.5f);
		btnOverlay.setAlignmentY(0.5f);

		JPanel leftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
		leftBtns.setOpaque(false);

		JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
		rightBtns.setOpaque(false);

		heatmapBtn.setOnToggleOn(() -> runHeatmapAsync(java.util.Set.of()));
		heatmapBtn.setOnToggleOff(() -> {
			baselineStartSeconds = -1;
			mapPanel.clearHeatmap();
		});

		closeStopsBtn.setOnToggleOn(() -> mapPanel.setClosingMode(true));
		closeStopsBtn.setOnToggleOff(() -> {
			closedStationNames.clear();
			heatmapExcluded.clear();
			mapPanel.setClosedStations(closedStationNames);
			mapPanel.setClosingMode(false);
			if (heatmapBtn.isSelected()) runHeatmapAsync(java.util.Set.of());
		});

		mapPanel.setOnHeatmapStopClick(stop -> {
			if (!closedStationNames.contains(stop.name())) {
				for (var s : currentData.stops.values()) {
					if (s.stop_name.equals(stop.name()))
						heatmapExcluded.add(s.stop_id);
				}
				closedStationNames.add(stop.name());
				mapPanel.setClosedStations(closedStationNames);
				if (heatmapBtn.isSelected()) runHeatmapAsync(new java.util.HashSet<>(heatmapExcluded));
			}
		});

		mapPanel.setOnClosedStationRemove(name -> {
			closedStationNames.remove(name);
			heatmapExcluded.clear();
			for (String n : closedStationNames)
				for (var s : currentData.stops.values())
					if (s.stop_name.equals(n)) heatmapExcluded.add(s.stop_id);
			mapPanel.setClosedStations(closedStationNames);
			if (heatmapBtn.isSelected()) runHeatmapAsync(new java.util.HashSet<>(heatmapExcluded));
		});

		leftBtns.add(heatmapBtn);
		leftBtns.add(closeStopsBtn);

		viewAnalysisBtn = buildAnalysisViewButton();
		filterBtn = buildFilterButton();
		leftBtns.add(viewAnalysisBtn);
		leftBtns.add(filterBtn);

		JButton runAnalysisBtn = new JButton("Run Closure Analysis");
		runAnalysisBtn.addActionListener(e -> new Thread(() -> {
			if (currentData == null) {
				SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "No GTFS data loaded.", "No data", JOptionPane.WARNING_MESSAGE));
				return;
			}
			RoutingEngine engine = new RoutingEngine();
			engine.loadData(currentData);
			ClosureAnalysis analysis = new ClosureAnalysis(engine, currentData);
			analysis.runAndWriteCSV("closure_analysis.csv");
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Done! Saved to closure_analysis.csv", "Analysis complete", JOptionPane.INFORMATION_MESSAGE));
		}).start());

		rightBtns.add(runAnalysisBtn);
		btnOverlay.add(leftBtns, BorderLayout.WEST);
		btnOverlay.add(rightBtns, BorderLayout.EAST);

		mapWrapper.add(btnOverlay);
		mapWrapper.add(mapPanel);

        add(buildSidebar(), BorderLayout.WEST);
		add(mapWrapper, BorderLayout.CENTER);
        setVisible(true);

    }




	private void runHeatmapAsync(Set<String> snapshot) {
		if (currentData == null) {
			JOptionPane.showMessageDialog(this, "No GTFS data loaded.", "No data", JOptionPane.WARNING_MESSAGE);
			heatmapBtn.revertToOff();
			return;
		}
		Location from = locationByName(fromField.getText().trim());
		if (from == null) {
			JOptionPane.showMessageDialog(this, "Select an origin stop first.", "No origin", JOptionPane.WARNING_MESSAGE);
			heatmapBtn.revertToOff();
			return;
		}
		boolean isBaseline = snapshot.isEmpty();
		if (isBaseline) {
			java.time.LocalTime now = java.time.LocalTime.now();
			baselineStartSeconds = now.getHour() * 3600 + now.getMinute() * 60;
		}
		int startSeconds = baselineStartSeconds;
		new Thread(() -> {
			try {
				RoutingEngine engine = new RoutingEngine();
				engine.loadData(currentData);
				Coordinates fromC = new Coordinates(from.lat(), from.lon());
				Map<String, Integer> result = engine.computeAllRoutes(fromC, startSeconds, snapshot, java.util.Set.of());

				System.err.println("Result size before filter: " + result.size());
				result.entrySet().removeIf(e -> {
					var stop = currentData.stops.get(e.getKey());
					if (stop == null) {
						System.err.println("No stop found for key: " + e.getKey());
						return true;
					}
					double km = haversine(from.lat(), from.lon(), stop.stop_lat, stop.stop_lon);
					if (km > 50.0) System.err.println("Filtered: " + stop.stop_name + " at " + km + "km");
					return km > 50.0;
				});

				System.err.println("Result size after filter: " + result.size());

				int max = result.values().stream().mapToInt(v -> v - startSeconds).max().orElse(3600);
				Set<String> validIds = new java.util.HashSet<>();
				for (String sid : result.keySet()) {
					var s = currentData.stops.get(sid);
					if (s != null) validIds.add(s.stop_name);
				}
				SwingUtilities.invokeLater(() -> {
					if (isBaseline) {
						Map<String, double[]> coords = new java.util.HashMap<>();
						for (var s : currentData.stops.values())
							coords.put(s.stop_id, new double[]{s.stop_lat, s.stop_lon});
						mapPanel.setHeatmapStopCoords(coords);
						mapPanel.setHeatmapBaseline(result);
					} else {
						mapPanel.activateDelta();
					}
					mapPanel.setHeatmapValidStops(validIds);
					mapPanel.setHeatmap(result, startSeconds, max);
				});
			} catch (Exception ex) {
				SwingUtilities.invokeLater(() -> {
					heatmapBtn.revertToOff();
					JOptionPane.showMessageDialog(Window.this, ex.getMessage(), "Heatmap error", JOptionPane.ERROR_MESSAGE);
				});
			}
		}).start();
	}

    public void loadStops(List<Location> stops) {
    	System.err.println("loadStops called with " + stops.size() + " stops");
    	mapPanel.setLoadingMessage(null); 

		mapPanel.repaint();
		mapPanel.revalidate();
        this.loadedStops = stops;
    	System.err.println("loading message cleared");


		fromField.setEnabled(true);
		toField.setEnabled(true);
		fromField.setText("Search origin...");
		toField.setForeground(Theme.TEXT_LIGHT);
		toField.setText("Search destination...");
		toField.setForeground(Theme.TEXT_LIGHT);

        mapPanel.setStops(stops);
        clearResults();

    }

    public void loadGTFSData(GTFSData data, String path) {
        Map<String, Location> byName = new LinkedHashMap<>();
        for (var s : data.stops.values())
            byName.putIfAbsent(s.stop_name, new Location(s.stop_id, s.stop_name, s.stop_lat, s.stop_lon));
        List<Location> stops = new ArrayList<>(byName.values());
        TileCache tc = null;
        try { tc = new TileCache(); tc.setCity(path); } catch (IOException ignored) {}
        TileCache finalTc = tc;
        SwingUtilities.invokeLater(() -> {
            this.currentData = data;
            loadStops(stops);
            if (finalTc != null) mapPanel.setTileCache(finalTc);
            mapPanel.repaint();
        });
    }

    /* public void showRoute(RouteModel.Route route) {
        Location from = locationByName(route.from());
        Location to   = locationByName(route.to());
        showResults(route);
        if (from != null && to != null) mapPanel.setRoute(from, to, route.mode(), null);
    }*/


    private JPanel buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.PANEL_BG);
        side.setPreferredSize(new Dimension(300, 0));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER));

        resultsPanel = new JPanel();
        resultsPanel.setBackground(Theme.PANEL_BG);
        resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
        resultsPanel.setBorder(new EmptyBorder(0, 0, 12, 0));

        JScrollPane scroll = new JScrollPane(resultsPanel);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(14);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.PANEL_BG);
        top.add(buildHeader(), BorderLayout.NORTH);
        top.add(buildForm(),   BorderLayout.CENTER);

        side.add(top,    BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        return side;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setBackground(Theme.HEADER_BG);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(new EmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("Berlin Transit");
        title.setFont(Theme.bold(17));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(0f);

        JLabel sub = new JLabel("Plan your journey across the city");
        sub.setFont(Theme.regular(11));
        sub.setForeground(new Color(0x94A3B8));
        sub.setAlignmentX(0f);

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(sub);
        return header;
    }

	private JPanel buildForm() {
		JPanel form = new JPanel();
		form.setBackground(Theme.PANEL_BG);
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
		form.setBorder(new EmptyBorder(16, 16, 14, 16));

		fromLabel = new ModeButton("📍 From (active)", Theme.ACCENT_GREEN, true);
		toLabel   = new ModeButton("📍 To",            Theme.ACCENT_RED,   false);

		ButtonGroup targetGroup = new ButtonGroup();
		targetGroup.add(fromLabel);
		targetGroup.add(toLabel);

		fromLabel.addActionListener(e -> clickTarget = "from");
		toLabel.addActionListener(e -> clickTarget = "to");

		JPanel targetRow = new JPanel(new GridLayout(1, 2, 6, 0));
		targetRow.setBackground(Theme.PANEL_BG);
		targetRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		targetRow.add(fromLabel);
		targetRow.add(toLabel);

		form.add(targetRow);
		form.add(Box.createVerticalStrut(8));
		form.add(fieldWithClear(fromField, "Search origin..."));
		form.add(Box.createVerticalStrut(4));
		form.add(swapButton());
		form.add(Box.createVerticalStrut(4));
		form.add(fieldWithClear(toField, "Search destination..."));
		form.add(Box.createVerticalStrut(16));
		form.add(fieldLabel("Click mode"));
		form.add(Box.createVerticalStrut(7));
		form.add(buildModeRow());
		form.add(Box.createVerticalStrut(16));
		PlanButton plan = new PlanButton("Plan Route");
		plan.addActionListener(e -> planRoute());
		form.add(plan);
		return form;
	}

	private JPanel buildModeRow() {
		JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
		row.setBackground(Theme.PANEL_BG);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));

		btnBus  = new ModeButton("Snap to station", Theme.ROUTE_BUS,  true);
		btnWalk = new ModeButton("Free click",      Theme.ACCENT,     false);

		ButtonGroup grp = new ButtonGroup();
		grp.add(btnBus); grp.add(btnWalk);

		ActionListener onMode = e -> {
			selMode = btnBus.isSelected() ? "snap" : "free";
			mapPanel.setSnapMode("snap".equals(selMode));
			row.repaint();
		};

		btnBus.addActionListener(onMode);
		btnWalk.addActionListener(onMode);

		row.add(btnBus); row.add(btnWalk);
		return row;
	}


	private void planRoute() {
		if (loadedStops.isEmpty()) {
			JOptionPane.showMessageDialog(this, "No GTFS data is loaded yet.", "No data", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String fn = fromField.getText().trim();
		String tn = toField.getText().trim();
		if (fn.isEmpty() || fn.equals("Search origin...") || 
			tn.isEmpty() || tn.equals("Search destination...")) {
			JOptionPane.showMessageDialog(this, "Please select both a starting point and a destination.", "Missing selection", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (fn.equals(tn)) {
			JOptionPane.showMessageDialog(this, "Origin and destination cannot be the same.", "Same location", JOptionPane.WARNING_MESSAGE);
			return;
		}
		Location from = locationByName(fn);
		Location to   = locationByName(tn);
		if (from == null || to == null) return;

		showPendingResult(fn, tn);

		new Thread(() -> {
			try {
				RoutingEngine engine = new RoutingEngine();
				engine.loadData(currentData);
				Coordinates fromC = new Coordinates(from.lat(), from.lon());
				Coordinates toC   = new Coordinates(to.lat(), to.lon());

				java.time.LocalTime now = java.time.LocalTime.now();
				String time = String.format("%02d:%02d", now.getHour(), now.getMinute());
				List<Map<String, Object>> steps = (List<Map<String, Object>>) engine.computeRoutePublic(fromC, toC, time, new java.util.HashSet<>(heatmapExcluded));

				SwingUtilities.invokeLater(() -> {
					mapPanel.setRoute(from, to, selMode, steps);
					showResults(from, to, steps);
				});
			} catch (Exception ex) {
				SwingUtilities.invokeLater(() ->
					JOptionPane.showMessageDialog(Window.this, ex.getMessage(), "Routing error", JOptionPane.ERROR_MESSAGE));
			}
		}).start();
	}


	private void showResults(Location from, Location to, List<Map<String, Object>> steps) {
		resultsPanel.removeAll();

		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(new Color(0xF1F5F9));
		card.setBorder(new CompoundBorder(
			new EmptyBorder(12, 14, 0, 14),
			new CompoundBorder(
				BorderFactory.createLineBorder(Theme.DIVIDER),
				new EmptyBorder(14, 14, 14, 14))));

		JLabel route = new JLabel(from.name() + " → " + to.name());
		route.setFont(Theme.bold(13));
		route.setForeground(Theme.TEXT_DARK);
		route.setAlignmentX(0f);
		card.add(route);
		card.add(Box.createVerticalStrut(6));

		int totalMins = steps.stream()
			.mapToInt(s -> s.get("duration") instanceof Number ? ((Number)s.get("duration")).intValue() : 0)
			.sum();
		JLabel duration = new JLabel("Total: " + totalMins + " min");
		duration.setFont(Theme.regular(12));
		duration.setForeground(Theme.TEXT_LIGHT);
		duration.setAlignmentX(0f);
		card.add(duration);
		resultsPanel.add(card);

		JPanel stepsPanel = new JPanel();
		stepsPanel.setLayout(new BoxLayout(stepsPanel, BoxLayout.Y_AXIS));
		stepsPanel.setBackground(Theme.PANEL_BG);
		stepsPanel.setBorder(new EmptyBorder(6, 14, 8, 14));

		for (int i = 0; i < steps.size(); i++) {
			Map<String, Object> step = steps.get(i);
			String mode     = (String) step.get("mode");
			int    duration2 = step.get("duration") instanceof Number ? ((Number)step.get("duration")).intValue() : 0;
			String startTime = (String) step.get("startTime");
			String stopName  = step.get("stop") instanceof String ? (String)step.get("stop") : null;

			String desc;
			if ("walk".equals(mode)) {
				desc = "Walk " + duration2 + " min";
			} else {
				Map<?,?> routeInfo = (Map<?,?>) step.get("route");
				String line = routeInfo != null ? (String)routeInfo.get("shortName") : "?";
				String head = routeInfo != null ? (String)routeInfo.get("headSign") : "";
				desc = "Ride " + line + " → " + head;
			}

			JPanel row = new JPanel(new BorderLayout(10, 0));
			row.setBackground(Theme.PANEL_BG);
			row.setBorder(new EmptyBorder(8, 2, 8, 2));

			Color modeColor = "walk".equals(mode) ? Theme.ROUTE_WALK : Theme.ROUTE_BUS;
			JLabel badge = new JLabel(String.valueOf(i + 1), SwingConstants.CENTER) {
				@Override protected void paintComponent(Graphics g) {
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g2.setColor(modeColor);
					int size = Math.min(getWidth(), getHeight());
					g2.fillOval((getWidth() - size) / 2, (getHeight() - size) / 2, size, size);
					g2.dispose();
					super.paintComponent(g);
				}
			};
			badge.setFont(Theme.bold(10));
			badge.setForeground(Color.WHITE);
			badge.setOpaque(false);
			badge.setPreferredSize(new Dimension(22, 22));
			badge.setMinimumSize(new Dimension(22, 22));
			badge.setMaximumSize(new Dimension(22, 22));
			row.add(badge, BorderLayout.WEST);

			JPanel text = new JPanel();
			text.setOpaque(false);
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
			JLabel descLabel = new JLabel(desc);
			descLabel.setFont(Theme.regular(12));
			descLabel.setForeground(Theme.TEXT_DARK);
			text.add(descLabel);
			if (stopName != null) {
				JLabel stop = new JLabel("Exit at " + stopName);
				stop.setFont(Theme.bold(11));
				stop.setForeground(modeColor);
				text.add(stop);
			}
			row.add(text, BorderLayout.CENTER);

			JLabel timeLabel = new JLabel(startTime + " · " + duration2 + " min");
			timeLabel.setFont(Theme.regular(11));
			timeLabel.setForeground(Theme.TEXT_LIGHT);
			row.add(timeLabel, BorderLayout.EAST);

			stepsPanel.add(row);
			if (i < steps.size() - 1) stepsPanel.add(thinDivider());
		}

		resultsPanel.add(stepsPanel);
		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

    private void showPendingResult(String from, String to) {
        resultsPanel.removeAll();

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(new Color(0xF1F5F9));
        card.setBorder(new CompoundBorder(
            new EmptyBorder(12, 14, 0, 14),
            new CompoundBorder(
                BorderFactory.createLineBorder(Theme.DIVIDER),
                new EmptyBorder(14, 14, 14, 14))));

        JLabel route = new JLabel(from + " → " + to);
        route.setFont(Theme.bold(13));
        route.setForeground(Theme.TEXT_DARK);
        route.setAlignmentX(0f);

        JLabel pending = new JLabel("Route result will appear here");
        pending.setFont(Theme.regular(12));
        pending.setForeground(Theme.TEXT_LIGHT);
        pending.setAlignmentX(0f);

        card.add(route);
        card.add(Box.createVerticalStrut(6));
        card.add(pending);

        resultsPanel.add(card);
        resultsPanel.revalidate();
        resultsPanel.repaint();
    }

    private void clearResults() {
        resultsPanel.removeAll();
        resultsPanel.revalidate();
        resultsPanel.repaint();
        mapPanel.clearRoute();
    }




    private JPanel statWidget(String value, String label) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel v = new JLabel(value);
        v.setFont(Theme.bold(15));
        v.setForeground(Theme.TEXT_DARK);
        JLabel l = new JLabel(label);
        l.setFont(Theme.regular(11));
        l.setForeground(Theme.TEXT_MID);
        p.add(v); p.add(l);
        return p;
    }

    private JPanel thinDivider() {
        JPanel d = new JPanel();
        d.setBackground(Theme.DIVIDER);
        d.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        d.setPreferredSize(new Dimension(Integer.MAX_VALUE, 1));
        return d;
    }


    private void resetDropdowns() {
	  	fromField.setText("Search origin...");
		fromField.setForeground(Theme.TEXT_LIGHT);
		fromField.setEnabled(false);
		toField.setText("Search destination...");
		toField.setForeground(Theme.TEXT_LIGHT);
		toField.setEnabled(false);
    }

    private Location locationByName(String name) {
        return loadedStops.stream().filter(l -> l.name().equals(name)).findFirst().orElse(null);
    }

    private JLabel dotLabel(String text, Color dot) {
        JLabel l = new JLabel("  " + text);
        l.setFont(Theme.bold(12));
        l.setForeground(Theme.TEXT_MID);
        l.setAlignmentX(0f);
        l.setIcon(new Icon() {
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(dot);
                g2.fillOval(x, y + 2, 8, 8);
            }
            public int getIconWidth()  { return 10; }
            public int getIconHeight() { return 12; }
        });
        return l;
    }

    private JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.bold(12));
        l.setForeground(Theme.TEXT_MID);
        l.setAlignmentX(0f);
        return l;
    }

	private JPanel fieldWithClear(JTextField field, String placeholder) {
		JButton btn = new JButton("×");
		btn.setFont(Theme.regular(15));
		btn.setForeground(Theme.TEXT_LIGHT);
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setFocusPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setPreferredSize(new Dimension(60, 50));
		btn.addActionListener(e -> {
			field.setText(placeholder);
			field.setForeground(Theme.TEXT_LIGHT);
			mapPanel.repaint();
		});
		JPanel row = new JPanel(new BorderLayout(0, 0));
		row.setBackground(Theme.PANEL_BG);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		row.add(field, BorderLayout.CENTER);
		row.add(btn, BorderLayout.EAST);
		return row;
	}

    private JButton swapButton() {
        JButton btn = new JButton("⇅  Swap");
        btn.setFont(Theme.regular(11));
        btn.setForeground(Theme.TEXT_MID);
        btn.setBackground(Theme.PANEL_BG);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(0f);
        btn.addActionListener(e -> {
			String fn = fromField.getText();
			String tn = toField.getText();
			fromField.setText(tn);
			toField.setText(fn);
			mapPanel.repaint();
        });
        return btn;
    }

	private JTextField styledSearchField(String placeholder) {
		JTextField f = new JTextField();
		f.setFont(Theme.regular(13));
		f.setForeground(Theme.TEXT_DARK);
		f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
		f.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.DIVIDER),
			new EmptyBorder(4, 8, 4, 8)));
		f.setText(placeholder);
		f.setForeground(Theme.TEXT_LIGHT);
		f.addFocusListener(new FocusAdapter() {
			public void focusGained(FocusEvent e) {
				if (f.getText().equals(placeholder)) {
					f.setText("");
					f.setForeground(Theme.TEXT_DARK);
				}
			}
			public void focusLost(FocusEvent e) {
				if (f.getText().isEmpty()) {
					f.setText(placeholder);
					f.setForeground(Theme.TEXT_LIGHT);
				}
			}
		});
		return f;
	}

	private void wireAutocomplete(JTextField field, boolean isFrom) {
		JWindow popup = new JWindow(this);
		JList<String> list = new JList<>();
		list.setFont(Theme.regular(12));
		list.setSelectionBackground(Theme.ACCENT);
		list.setSelectionForeground(Color.WHITE);
		list.setBackground(Color.WHITE);
		list.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER));
		popup.add(new JScrollPane(list));
		popup.setFocusableWindowState(false);
		popup.setSize(268, 160);

		boolean[] selecting = {false};

		field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			public void insertUpdate(javax.swing.event.DocumentEvent e)  { update(); }
			public void removeUpdate(javax.swing.event.DocumentEvent e)  { update(); }
			public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }

			void update() {
				if (selecting[0]) return;
				String text = field.getText().trim().toLowerCase();
				if (text.isEmpty() || text.equals("search origin...") || text.equals("search destination...") || loadedStops.isEmpty()) {
					popup.setVisible(false);
					return;
				}
				String[] matches = loadedStops.stream()
					.filter(l -> l.name().toLowerCase().contains(text))
					.limit(8)
					.map(Location::name)
					.toArray(String[]::new);

				if (matches.length == 0) { popup.setVisible(false); return; }

				list.setListData(matches);
				Point p = field.getLocationOnScreen();
				popup.setLocation(p.x, p.y + field.getHeight());
				popup.setVisible(true);
			}
		});

		list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) {
				String selected = list.getSelectedValue();
				if (selected == null) return;
				selecting[0] = true;
				field.setText(selected);
				field.setForeground(Theme.TEXT_DARK);
				popup.setVisible(false);
				selecting[0] = false;
				mapPanel.repaint();
			}
		});
	}

    private static class ModeButton extends JToggleButton {
        private final Color activeColor;

        ModeButton(String text, Color active, boolean selected) {
            super(text, selected);
            this.activeColor = active;
            setFont(Theme.bold(12));
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isSelected()) {
                g.setColor(activeColor);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                setForeground(Color.WHITE);
            } else {
                g.setColor(Theme.PANEL_BG);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g.setColor(Theme.DIVIDER);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                setForeground(Theme.TEXT_MID);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }


    private static class PlanButton extends JButton {
        PlanButton(String text) {
            super(text);
            setFont(Theme.bold(14));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setOpaque(false);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getModel().isRollover() ? new Color(0x2563EB) : Theme.ACCENT);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.dispose();
            super.paintComponent(g0);
        }
    }


	private JToggleButton buildAnalysisViewButton() {
		JToggleButton btn = new JToggleButton("📊  Analysis View") {
			@Override protected void paintComponent(Graphics g0) {
				Graphics2D g = (Graphics2D) g0.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (isSelected()) {
					g.setColor(new Color(0x7C3AED));
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
					setForeground(Color.WHITE);
				} else {
					g.setColor(getModel().isRollover() ? new Color(0xF5F3FF) : Color.WHITE);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
					g.setColor(Theme.DIVIDER);
					g.setStroke(new BasicStroke(1f));
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
					setForeground(Theme.TEXT_MID);
				}
				g.dispose();
				super.paintComponent(g0);
			}
		};
		btn.setFont(Theme.bold(12));
		btn.setPreferredSize(new Dimension(148, 36));
		btn.setFocusPainted(false);
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setOpaque(false);
		btn.addActionListener(e -> {
			if (btn.isSelected()) {
				loadAndShowAnalysis();
			} else {
				mapPanel.clearAnalysisRoutes();
				filterBtn.setEnabled(false);
			}
		});
		return btn;
	}

	private JButton buildFilterButton() {
		JButton btn = new JButton("Filters ▾") {
			@Override protected void paintComponent(Graphics g0) {
				Graphics2D g = (Graphics2D) g0.create();
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				if (!isEnabled()) {
					g.setColor(new Color(0xF8FAFC));
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
					setForeground(Theme.TEXT_LIGHT);
				} else {
					g.setColor(getModel().isRollover() ? new Color(0xEFF6FF) : Color.WHITE);
					g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
					g.setColor(Theme.DIVIDER);
					g.setStroke(new BasicStroke(1f));
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
					setForeground(Theme.TEXT_MID);
				}
				g.dispose();
				super.paintComponent(g0);
			}
		};
		btn.setFont(Theme.bold(12));
		btn.setPreferredSize(new Dimension(96, 36));
		btn.setFocusPainted(false);
		btn.setContentAreaFilled(false);
		btn.setBorderPainted(false);
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.setOpaque(false);
		btn.setEnabled(false);

		JPopupMenu menu = buildFilterMenu();
		btn.addActionListener(e -> menu.show(btn, 0, btn.getHeight() + 4));
		return btn;
	}

	private JPopupMenu buildFilterMenu() {
		JPopupMenu menu = new JPopupMenu();
		menu.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.DIVIDER),
			new EmptyBorder(4, 0, 4, 0)));

		menu.add(menuSectionLabel("TIER FILTER"));
		cbHigh    = checkedItem("■  High impact (worst to close)",  new Color(0xDC2626), true);
		cbMedHigh = checkedItem("■  Medium-high impact",            new Color(0xF97316), true);
		cbMedLow  = checkedItem("■  Medium-low impact",             new Color(0xEAB308), true);
		cbLow     = checkedItem("■  Low impact (best to close)",    new Color(0x22C55E), true);
		cbZero    = checkedItem("■  Zero impact (grey)",            new Color(0x94A3B8), false);
		for (JCheckBoxMenuItem cb : List.of(cbHigh, cbMedHigh, cbMedLow, cbLow, cbZero)) {
			cb.addActionListener(e -> applyAnalysisFilters());
			menu.add(cb);
		}

		menu.addSeparator();
		menu.add(menuSectionLabel("ROUTE TYPE"));
		cbBus      = checkedItem("Bus",          null, true);
		cbMetroTram = checkedItem("Metro / Tram", null, true);
		cbRail     = checkedItem("Rail",         null, true);
		cbOther    = checkedItem("Other",        null, true);
		for (JCheckBoxMenuItem cb : List.of(cbBus, cbMetroTram, cbRail, cbOther)) {
			cb.addActionListener(e -> applyAnalysisFilters());
			menu.add(cb);
		}

		return menu;
	}

	private JMenuItem menuSectionLabel(String text) {
		JMenuItem item = new JMenuItem(text);
		item.setFont(Theme.bold(10));
		item.setForeground(Theme.TEXT_LIGHT);
		item.setEnabled(false);
		item.setBorderPainted(false);
		return item;
	}

	private JCheckBoxMenuItem checkedItem(String text, Color dotColor, boolean selected) {
		JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
		item.setFont(Theme.regular(12));
		item.setForeground(dotColor != null ? dotColor : Theme.TEXT_DARK);
		return item;
	}

	private void loadAndShowAnalysis() {
		if (currentData == null) {
			JOptionPane.showMessageDialog(this, "Load GTFS data first.", "No data", JOptionPane.WARNING_MESSAGE);
			viewAnalysisBtn.setSelected(false);
			return;
		}
		File csvFile = new File("closure_analysis.csv");
		if (!csvFile.exists()) {
			JFileChooser chooser = new JFileChooser(".");
			chooser.setDialogTitle("Select closure_analysis.csv");
			chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CSV files", "csv"));
			if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
				viewAnalysisBtn.setSelected(false);
				return;
			}
			csvFile = chooser.getSelectedFile();
		}
		try {
			analysisEntries = parseAnalysisCSV(csvFile);
			filterBtn.setEnabled(true);
			applyAnalysisFilters();
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(this, "Failed to load CSV: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			viewAnalysisBtn.setSelected(false);
			filterBtn.setEnabled(false);
		}
	}

	private List<AnalysisEntry> parseAnalysisCSV(File file) throws IOException {
		List<AnalysisEntry> entries = new ArrayList<>();
		try (BufferedReader br = new BufferedReader(new FileReader(file))) {
			String line = br.readLine();
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) continue;
				String[] parts = line.split(",", -1);
				if (parts.length < 4) continue;
				String routeId   = parts[0].trim();
				String shortName = parts[1].trim();
				String longName  = parts[2].trim();
				double score;
				try { score = Double.parseDouble(parts[3].trim()); }
				catch (NumberFormatException e) { score = 0; }
				entries.add(new AnalysisEntry(routeId, shortName, longName, score));
			}
		}
		return entries;
	}

	private void applyAnalysisFilters() {
		if (analysisEntries == null || currentData == null) return;

		List<AnalysisEntry> filtered = new ArrayList<>();
		for (AnalysisEntry e : analysisEntries) {
			boolean include = switch (inferRouteType(e.routeId())) {
				case "Bus"        -> cbBus.isSelected();
				case "Metro/Tram" -> cbMetroTram.isSelected();
				case "Rail"       -> cbRail.isSelected();
				default           -> cbOther.isSelected();
			};
			if (include) filtered.add(e);
		}

		List<Double> nonZero = new ArrayList<>();
		for (AnalysisEntry e : filtered)
			if (e.score() > 0) nonZero.add(e.score());
		nonZero.sort(Double::compareTo);

		double p25 = percentile(nonZero, 0.25);
		double p50 = percentile(nonZero, 0.50);
		double p75 = percentile(nonZero, 0.75);

		Map<String, Color> routeColor = new LinkedHashMap<>();
		for (AnalysisEntry e : filtered) {
			Color c;
			if (e.score() == 0) {
				if (!cbZero.isSelected()) continue;
				c = new Color(0x94A3B8);
			} else if (e.score() >= p75) {
				if (!cbHigh.isSelected()) continue;
				c = new Color(0xDC2626);
			} else if (e.score() >= p50) {
				if (!cbMedHigh.isSelected()) continue;
				c = new Color(0xF97316);
			} else if (e.score() >= p25) {
				if (!cbMedLow.isSelected()) continue;
				c = new Color(0xEAB308);
			} else {
				if (!cbLow.isSelected()) continue;
				c = new Color(0x22C55E);
			}
			routeColor.put(e.routeId(), c);
		}

		Map<String, Trip> bestTrip = new HashMap<>();
		for (Trip trip : currentData.trips.values()) {
			if (!routeColor.containsKey(trip.routeId)) continue;
			Trip cur = bestTrip.get(trip.routeId);
			if (cur == null || trip.getStopTimes().size() > cur.getStopTimes().size())
				bestTrip.put(trip.routeId, trip);
		}

		List<double[][]> latLonsList = new ArrayList<>();
		List<Color> colorsList = new ArrayList<>();
		for (Map.Entry<String, Color> re : routeColor.entrySet()) {
			Trip trip = bestTrip.get(re.getKey());
			if (trip == null) continue;
			List<StopTime> sts = new ArrayList<>(trip.getStopTimes());
			sts.sort(Comparator.comparingInt(st -> st.stopSequence));
			List<double[]> pts = new ArrayList<>();
			for (StopTime st : sts) {
				Stop s = currentData.stops.get(st.stop_id);
				if (s != null) pts.add(new double[]{s.stop_lat, s.stop_lon});
			}
			if (pts.size() < 2) continue;
			latLonsList.add(pts.toArray(new double[0][]));
			colorsList.add(re.getValue());
		}

		mapPanel.setAnalysisRoutes(latLonsList, colorsList);
	}

	private double percentile(List<Double> sorted, double p) {
		if (sorted.isEmpty()) return 0;
		int idx = (int)(p * (sorted.size() - 1));
		return sorted.get(idx);
	}

	private String inferRouteType(String routeId) {
		String[] parts = routeId.split("_");
		if (parts.length < 2) return "Other";
		try {
			int type = Integer.parseInt(parts[parts.length - 1]);
			if (type == 3 || (type >= 700 && type < 800)) return "Bus";
			if (type == 0 || type == 1 || (type >= 900 && type < 1000)) return "Metro/Tram";
			if (type == 2 || (type >= 100 && type < 200) || (type >= 400 && type < 500)) return "Rail";
			return "Other";
		} catch (NumberFormatException e) {
			return "Other";
		}
	}

	private double haversine(double lat1, double lon1, double lat2, double lon2) {
		final double R = 6371.0;
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat/2) * Math.sin(dLat/2)
				 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
				 * Math.sin(dLon/2) * Math.sin(dLon/2);
		return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
	}

}

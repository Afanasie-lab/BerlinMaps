package util;

import java.io.File;
import java.util.Map;

import Structures.Coordinates;

public class RequestValidator {

	public static double extractDouble(Object value, String fieldName) throws Exception {
		if (value == null)
			throw new Exception("Bad request: '" + fieldName + "' is missing");
		if (!(value instanceof Number))
			throw new Exception("Bad request: '" + fieldName + "' must be a number, got: " + value);
		return ((Number) value).doubleValue();
	}

	public static String extractString(Object value, String fieldName) throws Exception {
		if (value == null || value.getClass().getSimpleName().equals("JSONNull"))
			throw new Exception("Bad request: '" + fieldName + "' is missing");
		if(!(value instanceof String)) 
			throw new Exception("Bad Request '" + fieldName + "' must be a String, got: " + value);
		return value.toString();
	}

	public static Map<?,?> extractMap(Object value, String fieldName) throws Exception {
		if (value == null || value.getClass().getSimpleName().equals("JSONNull"))
			throw new Exception("Bad request: '" + fieldName + "' is missing");
		if(!(value instanceof Map<?, ?>)) 
			throw new Exception("Bad Request '" + fieldName + "' must be a Map, got: " + value);
		return (Map<?, ?>) value;
	}

	public static Coordinates extractPoint(Object value, String fieldName) throws Exception {
		Map<?,?> map = extractMap(value, fieldName);
		double lat = extractDouble(map.get("lat"), fieldName + ".lat");
		double lon = extractDouble(map.get("lon"), fieldName + ".lon");
		return new Coordinates(lat, lon);
	}

	public static String extractFilename(Object value) throws Exception {
		String filename = extractString(value, "load");
		if (filename.isEmpty())
			throw new Exception("Bad request: filename cannot be empty");
		if (filename.startsWith("~/"))
			filename = System.getProperty("user.home") + filename.substring(1);
		File f = new File(filename);
		if (!f.exists()) throw new Exception("File not found: " + f.getAbsolutePath());
		return f.getAbsolutePath(); 
	}

}

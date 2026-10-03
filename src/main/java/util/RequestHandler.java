package util;

import java.util.Map;

public interface RequestHandler {
    void handle(Map<?, ?> request) throws Exception;
}


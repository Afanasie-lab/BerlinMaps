package Window;

import java.util.List;

class RouteModel {
    record Step(String desc, int mins, String line) {}
    record Route(String from, String to, String mode, int totalMins, double km, List<Step> steps) {}
}

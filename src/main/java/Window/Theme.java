package Window;

import java.awt.*;

final class Theme {
    static final Color BG           = new Color(0xECE9E2);
    static final Color ROAD_MAIN    = new Color(0xFFFFFF);
    static final Color ROAD_SEC     = new Color(0xEDE8DC);
    static final Color PARK         = new Color(0xBEDFC1);
    static final Color WATER        = new Color(0xA8CEED);
    static final Color BUILDING     = new Color(0xCCC5BE);
    static final Color PANEL_BG     = Color.WHITE;
    static final Color HEADER_BG    = new Color(0x1C2536);
    static final Color ACCENT       = new Color(0x3B82F6);
    static final Color ACCENT_GREEN = new Color(0x22C55E);
    static final Color ACCENT_RED   = new Color(0xEF4444);
    static final Color ROUTE_BUS    = new Color(0xDC2626);
    static final Color ROUTE_WALK   = new Color(0x16A34A);
    static final Color ROUTE_BIKE   = new Color(0xD97706);
    static final Color TEXT_DARK    = new Color(0x0F172A);
    static final Color TEXT_MID     = new Color(0x475569);
    static final Color TEXT_LIGHT   = new Color(0x94A3B8);
    static final Color DIVIDER      = new Color(0xE2E8F0);

    static Font regular(int size) { return new Font("SansSerif", Font.PLAIN, size); }
    static Font bold(int size)    { return new Font("SansSerif", Font.BOLD, size); }

    private Theme() {}
}

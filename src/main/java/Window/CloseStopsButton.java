package Window;

import javax.swing.*;
import java.awt.*;

public class CloseStopsButton extends JToggleButton {

    private Runnable onToggleOn;
    private Runnable onToggleOff;

    public CloseStopsButton() {
        super("⊘  Close Stops");
        setFont(Theme.bold(12));
        setPreferredSize(new Dimension(130, 36));
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);

        addActionListener(e -> {
            if (isSelected()) { if (onToggleOn  != null) onToggleOn.run(); }
            else               { if (onToggleOff != null) onToggleOff.run(); }
        });
    }

    public void setOnToggleOn(Runnable r)  { this.onToggleOn = r; }
    public void setOnToggleOff(Runnable r) { this.onToggleOff = r; }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (isSelected()) {
            g.setColor(new Color(0xDC2626));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            setForeground(Color.WHITE);
        } else {
            g.setColor(getModel().isRollover() ? new Color(0xFFF1F2) : Color.WHITE);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setColor(Theme.DIVIDER);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            setForeground(Theme.TEXT_MID);
        }
        g.dispose();
        super.paintComponent(g0);
    }
}

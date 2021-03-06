package net.runelite.client.plugins.bonburier;

import com.openosrs.client.ui.overlay.components.table.TableAlignment;
import com.openosrs.client.ui.overlay.components.table.TableComponent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.QuantityFormatter;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;

import static net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG;
import static net.runelite.client.ui.overlay.OverlayManager.OPTION_CONFIGURE;
import static org.apache.commons.lang3.time.DurationFormatUtils.formatDuration;

@Slf4j
@Singleton
public class BonBurierOverlay extends OverlayPanel {
    private final BonBurierPlugin plugin;
    private final BonBurierConfig config;

    String timeFormat;

    @Inject
    private BonBurierOverlay(final Client client, final BonBurierPlugin plugin, final BonBurierConfig config) {
        super(plugin);
        setPosition(OverlayPosition.BOTTOM_LEFT);
        this.plugin = plugin;
        this.config = config;
        getMenuEntries().add(new OverlayMenuEntry(RUNELITE_OVERLAY_CONFIG, OPTION_CONFIGURE, "BonBurier Overlay"));
        setPriority(OverlayPriority.HIGHEST);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (plugin.botTimer == null || !plugin.pluginRunning || !config.enableUI()) {
            return null;
        }

        TableComponent tableComponent = new TableComponent();
        tableComponent.setColumnAlignments(TableAlignment.LEFT, TableAlignment.RIGHT);
        Duration duration = Duration.between(plugin.botTimer, Instant.now());
        timeFormat = (duration.toHours() < 1) ? "mm:ss" : "HH:mm:ss";
        tableComponent.addRow("Time:", formatDuration(duration.toMillis(), timeFormat));

        if (plugin.burierState != null) {
            tableComponent.addRow("Status:", plugin.burierState.toString());
        } else {
            tableComponent.addRow("Status:", "ACTIVE");
        }

        TableComponent tableStatsComponent = new TableComponent();
        tableStatsComponent.setColumnAlignments(TableAlignment.LEFT, TableAlignment.RIGHT);
        tableStatsComponent.addRow("Bones Buried:", plugin.bonesBuried + " (" + QuantityFormatter.quantityToStackSize(plugin.getBonesPerHour()) + "/hr)");

        TableComponent tableDelayComponent = new TableComponent();
        tableDelayComponent.setColumnAlignments(TableAlignment.LEFT, TableAlignment.RIGHT);
        tableDelayComponent.addRow("Sleep delay:", plugin.sleepLength + "ms");
        tableDelayComponent.addRow("Tick delay:", String.valueOf(plugin.timeout));

        if (!tableComponent.isEmpty()) {
            panelComponent.setPreferredSize(new Dimension(200, 200));
            panelComponent.setBorder(new Rectangle(5, 5, 5, 5));
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("BonBurier")
                    .color(ColorUtil.fromHex("#40C4FF"))
                    .build());
            panelComponent.getChildren().add(tableComponent);
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Stats")
                    .color(ColorUtil.fromHex("#45D463"))
                    .build());
            panelComponent.getChildren().add(tableStatsComponent);
            panelComponent.getChildren().add(TitleComponent.builder()
                    .text("Delays")
                    .color(ColorUtil.fromHex("#F8BBD0"))
                    .build());
            panelComponent.getChildren().add(tableDelayComponent);
        }
        return super.render(graphics);
    }
}

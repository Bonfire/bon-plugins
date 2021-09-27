package net.runelite.client.plugins.boncleaner;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.*;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.awt.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;


@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
        name = "Bon Cleaner",
        description = "Cleans finds in the Varrock Museum"
)
@Slf4j

public class BonCleanerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private iUtils generalUtils;
    @Inject
    InventoryUtils inventoryUtils;
    @Inject
    MouseUtils mouseUtils;
    @Inject
    MenuUtils menuUtils;
    @Inject
    PlayerUtils playerUtils;
    @Inject
    CalculationUtils calcUtils;
    @Inject
    ObjectUtils objectUtils;

    @Inject
    private ConfigManager configManager;

    @Inject
    OverlayManager overlayManager;

    @Inject
    private BonCleanerConfig config;

    @Inject
    private BonCleanerOverlay overlay;

    // Timings
    int timeout = 0;
    long sleepLength;
    Instant botTimer;

    // To hold the current plugin status
    BonCleanerState cleanerState;
    boolean pluginRunning = false;
    int findsCleaned = 0;

    @Provides
    BonCleanerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BonCleanerConfig.class);
    }

    @Override
    protected void startUp() {
        botTimer = Instant.now();
        cleanerState = null;
        timeout = 0;
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        pluginRunning = false;
        timeout = 0;
        findsCleaned = 0;
    }

    @Subscribe
    private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("BonCleaner")) {
            return;
        }
        if (configButtonClicked.getKey().equals("startButton")) {
            if (!pluginRunning) {
                pluginRunning = true;
                botTimer = Instant.now();
                overlayManager.add(overlay);
                cleanerState = null;
            } else {
                shutDown();
            }
        }
    }

    private long sleepDelay() {
        sleepLength = calcUtils.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
        return sleepLength;
    }

    private int tickDelay() {
        return (int) calcUtils.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
    }

    private BonCleanerState getState() {
        // If there is time left on the tick time
        if (timeout > 0) {
            return BonCleanerState.TICK_TIMER;
        }

        Player localPlayer = client.getLocalPlayer();

        // If the player is null
        if (localPlayer == null) {
            return BonCleanerState.NULL_PLAYER;
        }

        // If the player is moving
        if (localPlayer.getPoseAnimation() != 813 && localPlayer.getPoseAnimation() != 5160 && localPlayer.getPoseAnimation() != 808) {
            timeout = tickDelay();
            return BonCleanerState.MOVING;
        }

        // If the player is animating
        if (localPlayer.getAnimation() != -1) {
            return BonCleanerState.ANIMATING;
        }

        // If the player's inventory doesn't contain tools
        if (!inventoryUtils.containsAllOf(new ArrayList<>(Arrays.asList(ItemID.TROWEL, ItemID.ROCK_PICK, ItemID.SPECIMEN_BRUSH)))) {
            return BonCleanerState.NEED_TOOLS;
        }

        // Otherwise, we don't currently care about the player's status
        return BonCleanerState.UNHANDLED_STATE;
    }

    @Subscribe
    private void onGameTick(GameTick gameTick) throws IOException {
        if (!pluginRunning) {
            return;
        }

        cleanerState = getState();

        switch (cleanerState) {
            case TICK_TIMER:
                playerUtils.handleRun(30, 20);
                timeout--;
                break;
            case ANIMATING:
            case MOVING:
                playerUtils.handleRun(30, 20);
                timeout = 1 + tickDelay();
                break;
            case NEED_TOOLS:
                Widget kitDialog = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTION1);
                if (kitDialog != null && kitDialog.getChild(0).getText().contains("cleaning kit")) {
                    generalUtils.sendGameMessage("Continuing dialog");
                    MenuEntry yesToolsEntry = new MenuEntry("Continue", "", 0, MenuAction.WIDGET_TYPE_6.getId(), 1, 14352385, false);
                    Rectangle yesRectangle = kitDialog.getBounds();
                    generalUtils.doActionMsTime(yesToolsEntry, yesRectangle, sleepDelay());
                    timeout = tickDelay();
                    break;
                }

                DecorativeObject toolsObject = objectUtils.findNearestDecorObject(ObjectID.TOOLS_24535);
                if (toolsObject != null) {
                    generalUtils.sendGameMessage("Clicking on tools");
                    MenuEntry takeToolsEntry = new MenuEntry("Take", "<col=ffff>Tools", 24535, MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), 51, 50, false);
                    Rectangle toolsRectangle = (toolsObject.getConvexHull() != null) ? toolsObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(takeToolsEntry, toolsRectangle, sleepDelay());
                }

                timeout = tickDelay();
                break;
        }
    }

    public long getFindsPerHour() {
        Duration duration = Duration.between(botTimer, Instant.now());
        return findsCleaned * (3600000 / duration.toMillis());
    }
}

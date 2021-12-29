package net.runelite.client.plugins.bonpumper;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.XpDropEvent;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.*;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
        name = "Bon Pumper",
        description = "Pumps at Blast Furnace"
)
@Slf4j
public class BonPumperPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private iUtils generalUtils;

    @Inject
    ObjectUtils objectUtils;

    @Inject
    PlayerUtils playerUtils;

    @Inject
    MouseUtils mouseUtils;

    @Inject
    MenuUtils menuUtils;

    @Inject
    CalculationUtils calcUtils;

    @Inject
    ContainerUtils containerUtils;

    @Inject
    private ConfigManager configManager;

    @Inject
    OverlayManager overlayManager;

    @Inject
    ItemManager itemManager;

    @Inject
    private BonPumperConfig config;

    @Inject
    private BonPumperOverlay overlay;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private SpriteManager spriteManager;

    // Timings
    int timeout = 0;
    long sleepLength;
    Instant botTimer;

    // To hold the current plugin status
    BonPumperState pumperState;
    boolean pluginRunning = false;

    // Provides our config
    @Provides
    BonPumperConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BonPumperConfig.class);
    }

    @Override
    protected void startUp() {
        botTimer = Instant.now();
        pumperState = null;
        setValues();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        setValues();
        pluginRunning = false;
        timeout = 0;
    }

    @Subscribe
    private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("BonPumper")) {
            return;
        }
        if (configButtonClicked.getKey().equals("startButton")) {
            if (!pluginRunning) {
                pluginRunning = true;
                botTimer = Instant.now();
                overlayManager.add(overlay);
                pumperState = null;
            } else {
                shutDown();
            }
        }
    }

    private void setValues() {
        timeout = 0;
    }


    private long sleepDelay() {
        sleepLength = calcUtils.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
        return sleepLength;
    }

    private int tickDelay() {
        return (int) calcUtils.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
    }

    private BonPumperState getState() {
        // If there is time left on the tick time
        if (timeout > 0) {
            return BonPumperState.TICK_TIMER;
        }

        Player localPlayer = client.getLocalPlayer();

        // If the player is null
        if (localPlayer == null) {
            return BonPumperState.NULL_PLAYER;
        }

        // If the player is moving
        if (playerUtils.isMoving()) {
            timeout = tickDelay();
            return BonPumperState.MOVING;
        }

        // If the player is animating
        if (localPlayer.getAnimation() != -1) {
            return BonPumperState.ANIMATING;
        }

        // Otherwise, we don't currently care about the player's status
        return BonPumperState.CLICK_PUMP;
    }

    @Subscribe
    private void onGameTick(GameTick gameTick) throws IOException {
        if (!pluginRunning) {
            return;
        }

        pumperState = getState();

        switch (pumperState) {
            case TICK_TIMER:
                playerUtils.handleRun(30, 20);
                timeout--;
                break;
            case ANIMATING:
            case MOVING:
                playerUtils.handleRun(30, 20);
                timeout = 1 + tickDelay();
                break;
            case CLICK_PUMP:
                GameObject pumpObject = objectUtils.findNearestGameObject(ObjectID.PUMP);

                if (pumpObject != null) {
                    LegacyMenuEntry operatePumpEntry = new LegacyMenuEntry("Operate", "<col=ffff>Pump", pumpObject.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), pumpObject.getLocalLocation().getSceneX(), pumpObject.getLocalLocation().getSceneY(), false);
                    Rectangle pumpRectangle = (pumpObject.getConvexHull() != null) ? pumpObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(operatePumpEntry, pumpRectangle, sleepDelay());
                }

                break;
        }
    }
}

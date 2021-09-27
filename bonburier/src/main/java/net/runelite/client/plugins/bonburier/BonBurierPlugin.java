package net.runelite.client.plugins.bonburier;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.queries.GameObjectQuery;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
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
import java.time.Duration;
import java.time.Instant;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
        name = "BonBurier",
        description = "Buries bones"
)
@Slf4j
public class BonBurierPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private iUtils generalUtils;

    @Inject
    BankUtils bankUtils;

    @Inject
    InventoryUtils inventoryUtils;

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
    private BonBurierConfig config;

    @Inject
    private BonBurierOverlay overlay;

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

    // Mouse handling
    GameObject targetObject;

    // To hold the current plugin status
    BonBurierState burierState;
    boolean pluginRunning = false;

    // For our paint
    int bonesBuried = 0;

    // Provides our config
    @Provides
    BonBurierConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BonBurierConfig.class);
    }

    @Override
    protected void startUp() {
        botTimer = Instant.now();
        burierState = null;
        setValues();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        setValues();
        pluginRunning = false;
        timeout = 0;
        bonesBuried = 0;
    }

    @Subscribe
    private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("BonBurier")) {
            return;
        }
        if (configButtonClicked.getKey().equals("startButton")) {
            if (!pluginRunning) {
                pluginRunning = true;
                botTimer = Instant.now();
                overlayManager.add(overlay);
                burierState = null;
            } else {
                shutDown();
            }
        }
    }

    private void setValues() {
        timeout = 0;
    }

    @Subscribe
    private void onXpDropEvent(XpDropEvent event)
    {
        if (event.getExp() <= 0) {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return;
        }

        if (event.getSkill().equals(Skill.PRAYER) && localPlayer.getAnimation() == AnimationID.BURYING_BONES) {
            bonesBuried++;
        }
    }

    private long sleepDelay() {
        sleepLength = calcUtils.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
        return sleepLength;
    }

    private int tickDelay() {
        return (int) calcUtils.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
    }

    private BonBurierState getState() {
        // If there is time left on the tick time
        if (timeout > 0) {
            return BonBurierState.TICK_TIMER;
        }

        Player localPlayer = client.getLocalPlayer();

        // If the player is null
        if (localPlayer == null) {
            return BonBurierState.NULL_PLAYER;
        }

        // If the player is moving
        if (localPlayer.getPoseAnimation() != 808) {
            timeout = tickDelay();
            return BonBurierState.MOVING;
        }

        // If the player is animating
        if (localPlayer.getAnimation() != -1) {
            return BonBurierState.ANIMATING;
        }

        // If the player's inventory is empty
        if (!bankUtils.isOpen() && inventoryUtils.isEmpty()) {
            return BonBurierState.INVENTORY_EMPTY;
        }

        // If the bank is open
        if (bankUtils.isOpen() && inventoryUtils.isEmpty()) {
            return BonBurierState.WITHDRAW_BONES;
        }

        // If the bank is open and the inventory is full
        if (bankUtils.isOpen() && inventoryUtils.isFull()) {
            return BonBurierState.CLOSE_BANK;
        }

        if (!bankUtils.isOpen() && inventoryUtils.containsItem(config.boneID())) {
            return BonBurierState.BURY_BONES;
        }

        // Otherwise, we don't currently care about the player's status
        return BonBurierState.UNHANDLED_STATE;
    }

    @Subscribe
    private void onGameTick(GameTick gameTick) throws IOException {
        if (!pluginRunning) {
            return;
        }

        burierState = getState();

        switch (burierState) {
            case TICK_TIMER:
                playerUtils.handleRun(30, 20);
                timeout--;
                break;
            case ANIMATING:
            case MOVING:
                playerUtils.handleRun(30, 20);
                timeout = 1 + tickDelay();
                break;
            case INVENTORY_EMPTY:
                openBank();
                burierState = getState();
                timeout = tickDelay();
                break;
            case WITHDRAW_BONES:
                bankUtils.withdrawAllItem(config.boneID());
                burierState = getState();
                timeout = tickDelay();
                break;
            case CLOSE_BANK:
                closeBank();
                burierState = getState();
                timeout = tickDelay();
                break;
            case BURY_BONES:
                buryBone();
                burierState = getState();
                timeout = tickDelay();
                break;
        }
    }

    private void openBank()
    {
        targetObject = new GameObjectQuery()
                .idEquals(config.bankObjectId())
                .result(client)
                .nearestTo(client.getLocalPlayer());
        if(targetObject != null){
            MenuEntry targetMenu = new MenuEntry("", "", targetObject.getId(), MenuAction.GAME_OBJECT_SECOND_OPTION.getId(), targetObject.getSceneMinLocation().getX(), targetObject.getSceneMinLocation().getY(), false);
            Rectangle targetRectangle = (targetObject.getConvexHull() != null) ? targetObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
            generalUtils.doActionMsTime(targetMenu, targetRectangle, sleepDelay());
        } else {
            generalUtils.sendGameMessage("Bank object is null.");
        }
    }

    public void closeBank() {
        bankUtils.close();
        timeout += tickDelay();
    }

    public void buryBone() {
        WidgetItem currentBone = inventoryUtils.getWidgetItem(config.boneID());

        if (currentBone != null) {
            MenuEntry targetMenu = new MenuEntry("", "", currentBone.getId(), MenuAction.ITEM_FIRST_OPTION.getId(), currentBone.getIndex(), WidgetInfo.INVENTORY.getId(), false);
            menuUtils.setEntry(targetMenu);
            mouseUtils.delayMouseClick(currentBone.getCanvasBounds(), sleepDelay());
        } else {
            generalUtils.sendGameMessage("No bones found in the inventory");
        }

        timeout = tickDelay();
    }

    public long getBonesPerHour() {
        Duration duration = Duration.between(botTimer, Instant.now());
        return bonesBuried * (3600000 / duration.toMillis());
    }

}

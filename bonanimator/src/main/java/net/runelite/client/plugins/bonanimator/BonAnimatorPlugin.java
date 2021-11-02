package net.runelite.client.plugins.bonanimator;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
import java.util.Arrays;
import java.util.List;

@Extension
@PluginDependency(iUtils.class)
@PluginDescriptor(
        name = "Bon Animator",
        description = "Animates and kills armor sets in the Warriors' Guild"
)
@Slf4j
public class BonAnimatorPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private iUtils generalUtils;

    @Inject
    InventoryUtils inventoryUtils;

    @Inject
    PlayerUtils playerUtils;

    @Inject
    MouseUtils mouseUtils;

    @Inject
    NPCUtils npcUtils;

    @Inject
    CalculationUtils calcUtils;

    @Inject
    ObjectUtils objectUtils;

    @Inject
    ContainerUtils containerUtils;

    @Inject
    private ConfigManager configManager;

    @Inject
    OverlayManager overlayManager;

    @Inject
    ItemManager itemManager;

    @Inject
    private BonAnimatorConfig animatorConfig;

    @Inject
    private BonAnimatorOverlay overlay;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ClientThread clientThread;

    @Inject
    private SpriteManager spriteManager;

    // Timings
    int tickTimeout = 0;
    long sleepLength;
    Instant botTimer;

    // To hold the current plugin status
    BonAnimatorState animatorState;
    boolean pluginRunning = false;

    // For our paint
    int tokensObtained = 0;

    List<Integer> blackArmorSet = Arrays.asList(ItemID.BLACK_FULL_HELM, ItemID.BLACK_PLATEBODY, ItemID.BLACK_PLATELEGS);
    List<Integer> mithrilArmorSet = Arrays.asList(ItemID.MITHRIL_FULL_HELM, ItemID.MITHRIL_PLATEBODY, ItemID.MITHRIL_PLATELEGS);
    List<Integer> adamantArmorSet = Arrays.asList(ItemID.ADAMANT_FULL_HELM, ItemID.ADAMANT_PLATEBODY, ItemID.ADAMANT_PLATELEGS);
    List<Integer> runeArmorSet = Arrays.asList(ItemID.RUNE_FULL_HELM, ItemID.RUNE_PLATEBODY, ItemID.RUNE_PLATELEGS);

    // Token amounts for Black, Mithril, Adamant, and Rune
    int[] tokenAmounts = { 20, 25, 30, 40 };

    // Provides our config
    @Provides
    BonAnimatorConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BonAnimatorConfig.class);
    }

    @Override
    protected void startUp() {
        botTimer = Instant.now();
        animatorState = null;
        setValues();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        setValues();
        pluginRunning = false;
        tickTimeout = 0;
        tokensObtained = 0;
        botTimer = null;
    }

    @Subscribe
    private void onConfigButtonPressed(ConfigButtonClicked configButtonClicked) {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("BonAnimator")) {
            return;
        }
        if (configButtonClicked.getKey().equals("startButton")) {
            if (!pluginRunning) {
                pluginRunning = true;
                botTimer = Instant.now();
                overlayManager.add(overlay);
                animatorState = null;
            } else {
                shutDown();
            }
        }
    }

    private void setValues() {
        tickTimeout = 0;
    }

    private long sleepDelay() {
        sleepLength = calcUtils.randomDelay(animatorConfig.sleepWeightedDistribution(), animatorConfig.sleepMin(), animatorConfig.sleepMax(), animatorConfig.sleepDeviation(), animatorConfig.sleepTarget());
        return sleepLength;
    }

    private int tickDelay() {
        return (int) calcUtils.randomDelay(animatorConfig.tickDelayWeightedDistribution(), animatorConfig.tickDelayMin(), animatorConfig.tickDelayMax(), animatorConfig.tickDelayDeviation(), animatorConfig.tickDelayTarget());
    }

    private BonAnimatorState getState() {
        // If there is time left on the tick time
        if (tickTimeout > 0) {
            return BonAnimatorState.TICK_TIMER;
        }

        // If the player is null
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return BonAnimatorState.NULL_PLAYER;
        }

        // If the player is moving
        if (localPlayer.isMoving()) {
            tickTimeout = tickDelay();
            return BonAnimatorState.MOVING;
        }

        // If the player is animating
        if (localPlayer.getAnimation() != -1) {
            return BonAnimatorState.ANIMATING;
        }

        if (npcUtils.getFirstNPCWithLocalTarget() != null) {
            return BonAnimatorState.IN_COMBAT;
        }

        if (objectUtils.getGroundItem(ItemID.WARRIOR_GUILD_TOKEN) != null) {
            return BonAnimatorState.LOOT_TOKENS;
        }

        if (!hasRequiredItems()) {
            return BonAnimatorState.LOOT_ARMOR;
        }

        if (hasRequiredItems()) {
            return BonAnimatorState.PLACE_ARMOR;
        }

        // Otherwise, we don't currently care about the player's status
        return BonAnimatorState.UNHANDLED_STATE;
    }

    @Subscribe
    private void onGameTick(GameTick gameTick) throws IOException {
        if (!pluginRunning) {
            return;
        }

        animatorState = getState();

        switch (animatorState) {
            case TICK_TIMER:
                tickTimeout--;
                break;
            case ANIMATING:
            case MOVING:
            case IN_COMBAT:
                tickTimeout = 1;
                break;
            case PLACE_ARMOR:
                // ID 23955 = Magical Animator
                GameObject magicalAnimator = objectUtils.findNearestGameObject(23955);
                if (magicalAnimator != null) {
                    MenuEntry animateArmorEntry = new MenuEntry("Animate", "<col=ffff>Magical Animator", magicalAnimator.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), magicalAnimator.getLocalLocation().getSceneX(), magicalAnimator.getLocalLocation().getSceneY(), false);
                    Rectangle animatorClickPoint = getClickPoint(magicalAnimator);
                    generalUtils.doActionMsTime(animateArmorEntry, animatorClickPoint, sleepDelay());
                    tickTimeout = tickDelay();
                }

                break;
            case LOOT_TOKENS:
                TileItem tokensItem = objectUtils.getGroundItem(ItemID.WARRIOR_GUILD_TOKEN);
                if (tokensItem != null) {
                    lootItem(tokensItem);

                    // Increment the token count depending on the current armor set
                    tokensObtained += tokenAmounts[animatorConfig.armorType().ordinal()];

                    tickTimeout = tickDelay();
                }
            case LOOT_ARMOR:
                TileItem armorToLoot = null;

                switch (animatorConfig.armorType()) {
                    case BLACK:
                        armorToLoot = getNearestTileItemByID(blackArmorSet);
                        break;
                    case MITHRIL:
                        armorToLoot = getNearestTileItemByID(mithrilArmorSet);
                        break;
                    case ADAMANT:
                        armorToLoot = getNearestTileItemByID(adamantArmorSet);
                        break;
                    case RUNE:
                        armorToLoot = getNearestTileItemByID(runeArmorSet);
                        break;
                }

                if (armorToLoot != null) {
                    lootItem(armorToLoot);
                }

                tickTimeout = 0;
                break;
        }
    }

    public Rectangle getClickPoint(GameObject gameObject) {
        return (gameObject.getConvexHull() != null) ? gameObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
    }

    private TileItem getNearestTileItemByID(List<Integer> itemIDs) {
        for (Integer itemID : itemIDs) {
            TileItem itemFromID = objectUtils.getGroundItem(itemID);
            if (itemFromID != null) {
                return itemFromID;
            }
        }

        return null;
    }

    private void lootItem(TileItem lootItem) {
        if (lootItem != null) {
            MenuEntry takeItemEntry = new MenuEntry("Take", "", lootItem.getId(), MenuAction.GROUND_ITEM_THIRD_OPTION.getId(),
                    lootItem.getTile().getSceneLocation().getX(), lootItem.getTile().getSceneLocation().getY(), false);
            Rectangle itemClickPoint = lootItem.getTile().getItemLayer().getCanvasTilePoly().getBounds();
            generalUtils.doActionMsTime(takeItemEntry, itemClickPoint, sleepDelay());
        }
    }

    public long getTokensPerHour() {
        Duration duration = Duration.between(botTimer, Instant.now());
        return tokensObtained * (3600000 / duration.toMillis());
    }

    public boolean hasRequiredItems() {
        switch (animatorConfig.armorType()) {
            case BLACK:
                return inventoryUtils.containsAllOf(blackArmorSet);
            case MITHRIL:
                return inventoryUtils.containsAllOf(mithrilArmorSet);
            case ADAMANT:
                return inventoryUtils.containsAllOf(adamantArmorSet);
            case RUNE:
                return inventoryUtils.containsAllOf(runeArmorSet);
        }

        return false;
    }
}

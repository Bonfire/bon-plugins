package net.runelite.client.plugins.boncleaner;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.ConfigButtonClicked;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.*;
import net.runelite.client.plugins.iutils.game.Game;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.awt.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.List;

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
    boolean shouldDrop = false;
    boolean isDropping = false;
    int dropIterationCount = 0;

    // Global utility objects
    @Inject
    private Game Game;

    List<Integer> TOOL_LIST = new ArrayList<>(Arrays.asList(ItemID.TROWEL, ItemID.ROCK_PICK, ItemID.SPECIMEN_BRUSH, ItemID.ANTIQUE_LAMP));
    List<Integer> JUNK_LIST = new ArrayList<>(Arrays.asList(ItemID.POTTERY, ItemID.OLD_SYMBOL, ItemID.ANCIENT_SYMBOL, ItemID.OLD_COIN, ItemID.ANCIENT_COIN, ItemID.CLEAN_NECKLACE, ItemID.JEWELLERY, ItemID.OLD_CHIPPED_VASE, ItemID.ARROWHEADS, ItemID.BROKEN_ARROW, ItemID.BROKEN_GLASS_1469, ItemID.IRON_DAGGER, ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.IRON_DAGGER, ItemID.BOWL, ItemID.POT, ItemID.IRON_ARROWTIPS, ItemID.IRON_KNIFE, ItemID.COAL, ItemID.IRON_BOLTS, ItemID.IRON_DART, ItemID.BRONZE_LIMBS, ItemID.BIG_BONES, ItemID.BONES, ItemID.WOODEN_STOCK, ItemID.TIN_ORE, ItemID.COPPER_ORE, ItemID.MITHRIL_ORE, ItemID.IRON_ORE, ItemID.COINS, ItemID.COINS_995));
    List<Integer> CLEANING_ANIMATIONS = new ArrayList<>(Arrays.asList(6459, 6217));

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
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) {
            return BonCleanerState.NULL_PLAYER;
        }

        // If the player is moving
        if (playerUtils.isMoving()) {
            timeout = tickDelay();
            return BonCleanerState.MOVING;
        }

        // If we're currently performing the find cleaning animation, we shouldn't attempt to clean more finds
        if (CLEANING_ANIMATIONS.contains(localPlayer.getAnimation())) {
            return BonCleanerState.CLEANING;
        }

        if (!inventoryUtils.containsItem(TOOL_LIST)) {
            return BonCleanerState.NEED_TOOLS;
        }

        // If we're currently performing the "bury bones" animation, we could be doing one of two things:
        // 1. Getting new uncleaned finds
        // 2. Turning in our cleaned finds
        if (localPlayer.getAnimation() == AnimationID.BURYING_BONES) {
            // If the inventory only contains tools or it contains uncleaned finds, we are getting new finds
            if (inventoryOnlyContains(TOOL_LIST) || inventoryUtils.containsItem(ItemID.UNCLEANED_FIND)) {
                if (config.quickTake()) {
                    return BonCleanerState.QUICK_GET_FINDS;
                } else {
                    return BonCleanerState.GETTING_FINDS;
                }
            }

            // If the inventory contains tools and has assorted "find items", we are depositing
            if (inventoryUtils.containsItem(TOOL_LIST) && inventoryUtils.containsItem(JUNK_LIST)) {
                return BonCleanerState.TURNING_IN;
            }
        }

        // If there is time left on the tick time
        if (timeout > 0) {
            return BonCleanerState.TICK_TIMER;
        }

        // Drop all junk items if we need to
        if (shouldDrop) {
            return BonCleanerState.DROP_JUNK;
        }

        // If the player's inventory contains an experience lamp, let's use it on the skill that the user chose
        if (inventoryUtils.containsItem(ItemID.ANTIQUE_LAMP_11189)) {
            return BonCleanerState.USE_LAMP;
        }

        // If the player is animating
        if (localPlayer.getAnimation() != -1) {
            return BonCleanerState.ANIMATING;
        }

        // If the inventory just contains tools (or lamps), we should pick up finds
        if (inventoryOnlyContains(TOOL_LIST) || (inventoryUtils.containsItem(TOOL_LIST) && !inventoryUtils.isFull() && inventoryUtils.containsItem(ItemID.UNCLEANED_FIND))) {
            if (config.quickTake()) {
                return BonCleanerState.QUICK_GET_FINDS;
            } else {
                return BonCleanerState.GET_FINDS;
            }
        }

        // If the inventory is full of both tools and uncleaned finds we should start cleaning
        if (inventoryUtils.containsItem(TOOL_LIST) && inventoryUtils.containsItem(ItemID.UNCLEANED_FIND) && inventoryUtils.isFull()) {
            return BonCleanerState.CLEAN_FINDS;
        }

        // If the inventory contains the tools, no uncleaned finds and assorted "find" items, we should deposit
        if (inventoryUtils.containsItem(TOOL_LIST) && !inventoryUtils.containsItem(ItemID.UNCLEANED_FIND) && inventoryUtils.containsItem(JUNK_LIST)) {
            return BonCleanerState.TURN_IN_FINDS;
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
                    LegacyMenuEntry yesToolsEntry = new LegacyMenuEntry("Continue", "", 0, MenuAction.WIDGET_TYPE_6.getId(), 1, 14352385, false);
                    Rectangle yesRectangle = kitDialog.getBounds();
                    generalUtils.doActionMsTime(yesToolsEntry, yesRectangle, sleepDelay());
                    timeout = tickDelay();
                    break;
                }

                DecorativeObject toolsObject = objectUtils.findNearestDecorObject(ObjectID.TOOLS_24535);
                if (toolsObject != null) {
                    LegacyMenuEntry takeToolsEntry = new LegacyMenuEntry("Take", "<col=ffff>Tools", 24535, MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), toolsObject.getLocalLocation().getSceneX(), toolsObject.getLocalLocation().getSceneY(), false);
                    Rectangle toolsRectangle = (toolsObject.getConvexHull() != null) ? toolsObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(takeToolsEntry, toolsRectangle, sleepDelay());
                    timeout = tickDelay();
                    break;
                }

                timeout = tickDelay();
                break;
            case GET_FINDS:
                // If we're getting finds, it means we're done dropping
                isDropping = false;

                GameObject rocksObject = objectUtils.findNearestGameObject(ObjectID.DIG_SITE_SPECIMEN_ROCKS, ObjectID.DIG_SITE_SPECIMEN_ROCKS_24558, ObjectID.DIG_SITE_SPECIMEN_ROCKS_24559);

                if (rocksObject != null) {
                    LegacyMenuEntry takeSpecimenEntry = new LegacyMenuEntry("Take", "<col=ffff>Dig Site specimen rocks", rocksObject.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), rocksObject.getLocalLocation().getSceneX(), rocksObject.getLocalLocation().getSceneY(), false);
                    Rectangle toolsRectangle = (rocksObject.getConvexHull() != null) ? rocksObject.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(takeSpecimenEntry, toolsRectangle, sleepDelay());
                }

                timeout = tickDelay();
                break;
            case QUICK_GET_FINDS:
                // If we're getting finds, it means we're done dropping
                isDropping = false;

                GameObject quickRocksObj = objectUtils.findNearestGameObject(ObjectID.DIG_SITE_SPECIMEN_ROCKS, ObjectID.DIG_SITE_SPECIMEN_ROCKS_24558, ObjectID.DIG_SITE_SPECIMEN_ROCKS_24559);

                if (quickRocksObj != null) {
                    LegacyMenuEntry takeSpecimenEntry = new LegacyMenuEntry("Take", "<col=ffff>Dig Site specimen rocks", quickRocksObj.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), quickRocksObj.getLocalLocation().getSceneX(), quickRocksObj.getLocalLocation().getSceneY(), false);
                    Rectangle toolsRectangle = (quickRocksObj.getConvexHull() != null) ? quickRocksObj.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(takeSpecimenEntry, toolsRectangle, sleepDelay());
                }

                break;
            case GETTING_FINDS:
                if (!inventoryUtils.isFull()) {
                    timeout = 3;
                } else {
                    timeout = tickDelay();
                }
                break;
            case CLEAN_FINDS:
                GameObject cleaningTable = objectUtils.findNearestGameObject(ObjectID.SPECIMEN_TABLE_24556);

                if (cleaningTable != null) {
                    LegacyMenuEntry cleanEntry = new LegacyMenuEntry("Clean", "<col=ffff>Specimen table", cleaningTable.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), cleaningTable.getSceneMinLocation().getX(), cleaningTable.getSceneMinLocation().getY(), false);
                    Rectangle tableRectangle = (cleaningTable.getConvexHull() != null) ? cleaningTable.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(cleanEntry, tableRectangle, sleepDelay());
                }

                timeout = tickDelay();
                break;
            case CLEANING:
                if (inventoryUtils.containsItem(ItemID.UNCLEANED_FIND)) {
                    timeout = 8;
                } else {
                    timeout = tickDelay();
                }
                break;
            case TURN_IN_FINDS:
                Widget turnInDialog = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTION1);
                if (turnInDialog != null && turnInDialog.getChild(0).getText().contains("placed in this crate")) {
                    LegacyMenuEntry yesToolsEntry = new LegacyMenuEntry("Continue", "", 0, MenuAction.WIDGET_TYPE_6.getId(), 1, 14352385, false);
                    Rectangle yesRectangle = turnInDialog.getBounds();
                    generalUtils.doActionMsTime(yesToolsEntry, yesRectangle, sleepDelay());
                    timeout = tickDelay();
                    break;
                }

                Widget noMoreFindsDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
                if (noMoreFindsDialog != null && noMoreFindsDialog.getText().contains("don't think")) {
                    shouldDrop = true;
                    timeout = tickDelay();
                    break;
                }

                GameObject storageCrate = objectUtils.findNearestGameObject(ObjectID.STORAGE_CRATE);
                if (storageCrate != null) {
                    LegacyMenuEntry turnInEntry = new LegacyMenuEntry("Add finds", "<col=ffff>Storage crate", storageCrate.getId(), MenuAction.GAME_OBJECT_FIRST_OPTION.getId(), storageCrate.getLocalLocation().getSceneX(), storageCrate.getLocalLocation().getSceneY(), false);
                    Rectangle crateRectangle = (storageCrate.getConvexHull() != null) ? storageCrate.getConvexHull().getBounds() : new Rectangle(client.getCenterX() - 50, client.getCenterY() - 50, 100, 100);
                    generalUtils.doActionMsTime(turnInEntry, crateRectangle, sleepDelay());
                }

                timeout = tickDelay();
                break;
            case TURNING_IN:
                Widget thankYouDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
                if (thankYouDialog != null && thankYouDialog.getText().contains("helping us")) {
                    shouldDrop = true;
                    timeout = tickDelay();
                    break;
                } else {
                    timeout = 5;
                    break;
                }
            case DROP_JUNK:
                if (inventoryUtils.containsItem(JUNK_LIST)) {
                    if (!isDropping || dropIterationCount > 5) {
                        inventoryUtils.dropItems(JUNK_LIST, true, config.sleepMin(), config.sleepMax());
                        isDropping = true;
                        dropIterationCount = 0;
                    }

                    dropIterationCount++;
                    timeout = tickDelay();
                    break;
                }

                shouldDrop = false;
                isDropping = false;
                dropIterationCount = 0;
                timeout = tickDelay();
                break;
            case USE_LAMP:
                // Use the lamp on the skill the user has selected (or a random one) if the interface is open
                Widget skillInterface = client.getWidget(240, 0);
                if (skillInterface != null && !skillInterface.isHidden()) {
                    selectExpSkill(config.xpSkill());
                    timeout = tickDelay();
                    break;
                }

                // Rub the lamp if the interface is not open
                WidgetItem expLamp = inventoryUtils.getWidgetItem(ItemID.ANTIQUE_LAMP_11189);
                if (expLamp != null) {
                    // Open the lamp interface
                    LegacyMenuEntry useLampEntry = new LegacyMenuEntry("Rub", "Rub", expLamp.getId(), MenuAction.ITEM_FIRST_OPTION.getId(), expLamp.getIndex(),  WidgetInfo.INVENTORY.getId(), false);
                    Rectangle itemBounds = expLamp.getCanvasBounds().getBounds();
                    generalUtils.doActionMsTime(useLampEntry, itemBounds, sleepDelay());
                    timeout = tickDelay();
                    break;
                }

                timeout = tickDelay();
                break;
        }
    }

    private boolean inventoryOnlyContains(List<Integer> itemIds) {
        if (client.getItemContainer(InventoryID.INVENTORY) == null) {
            return false;
        }

        Collection<WidgetItem> inventoryItems = inventoryUtils.getAllItems();
        for (WidgetItem item : inventoryItems) {
            if (!itemIds.contains(item.getId())) {
                return false;
            }
        }

        return true;
    }

    private void selectExpSkill(Skill skill) {
        Widget widgetToClick = null;
        String menuOption = null;

        switch (skill) {
            case ATTACK:
                widgetToClick = client.getWidget(240, 2);
                menuOption = "Attack";
                break;
            case STRENGTH:
                widgetToClick = client.getWidget(240, 3);
                menuOption = "Strength";
                break;
            case RANGED:
                widgetToClick = client.getWidget(240, 4);
                menuOption = "Ranged";
                break;
            case MAGIC:
                widgetToClick = client.getWidget(240, 5);
                menuOption = "Magic";
                break;
            case DEFENCE:
                widgetToClick = client.getWidget(240, 6);
                menuOption = "Defence";
                break;
            case HITPOINTS:
                widgetToClick = client.getWidget(240, 7);
                menuOption = "Hitpoints";
                break;
            case PRAYER:
                widgetToClick = client.getWidget(240, 8);
                menuOption = "Prayer";
                break;
            case AGILITY:
                widgetToClick = client.getWidget(240, 9);
                menuOption = "Agility";
                break;
            case HERBLORE:
                widgetToClick = client.getWidget(240, 10);
                menuOption = "Herblore";
                break;
            case THIEVING:
                widgetToClick = client.getWidget(240, 11);
                menuOption = "Thieving";
                break;
            case CRAFTING:
                widgetToClick = client.getWidget(240, 12);
                menuOption = "Crafting";
                break;
            case RUNECRAFT:
                widgetToClick = client.getWidget(240, 13);
                menuOption = "Runecraft";
                break;
            case SLAYER:
                widgetToClick = client.getWidget(240, 14);
                menuOption = "Slayer";
                break;
            case FARMING:
                widgetToClick = client.getWidget(240, 15);
                menuOption = "Farming";
                break;
            case MINING:
                widgetToClick = client.getWidget(240, 16);
                menuOption = "Mining";
                break;
            case SMITHING:
                widgetToClick = client.getWidget(240, 17);
                menuOption = "Smithing";
                break;
            case FISHING:
                widgetToClick = client.getWidget(240, 18);
                menuOption = "Fishing";
                break;
            case COOKING:
                widgetToClick = client.getWidget(240, 19);
                menuOption = "Cooking";
                break;
            case FIREMAKING:
                widgetToClick = client.getWidget(240, 20);
                menuOption = "Firemaking";
                break;
            case WOODCUTTING:
                widgetToClick = client.getWidget(240, 21);
                menuOption = "Woodcutting";
                break;
            case FLETCHING:
                widgetToClick = client.getWidget(240, 22);
                menuOption = "Fletching";
                break;
            case CONSTRUCTION:
                widgetToClick = client.getWidget(240, 23);
                menuOption = "Construction";
                break;
            case HUNTER:
                widgetToClick = client.getWidget(240, 24);
                menuOption = "Hunter";
                break;
        }

        Widget confirmWidget = client.getWidget(240, 26);
        if (confirmWidget != null && menuOption != null && confirmWidget.getChild(0).getText().contains(menuOption)) {
            LegacyMenuEntry confirmEntry = new LegacyMenuEntry("Confirm", "", 1, MenuAction.CC_OP.getId(), -1, confirmWidget.getId(), false);
            Rectangle confirmRectangle = confirmWidget.getBounds();
            generalUtils.doActionMsTime(confirmEntry, confirmRectangle, sleepDelay());
            timeout = tickDelay();
            return;
        }

        if (widgetToClick != null) {
            LegacyMenuEntry skillEntry = new LegacyMenuEntry(menuOption, "", 1, MenuAction.CC_OP.getId(), -1, widgetToClick.getId(), false);
            Rectangle skillRectangle = widgetToClick.getBounds();
            generalUtils.doActionMsTime(skillEntry, skillRectangle, sleepDelay());
            timeout = tickDelay();
        }
    }
}

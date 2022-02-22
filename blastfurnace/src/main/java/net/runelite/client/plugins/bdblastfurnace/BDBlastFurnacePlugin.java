package net.runelite.client.plugins.bdblastfurnace;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.Varbits;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.iutils.BankUtils;
import net.runelite.client.plugins.iutils.InventoryUtils;
import net.runelite.client.plugins.iutils.ObjectUtils;
import net.runelite.client.plugins.oneclickutils.LegacyMenuEntry;
import net.runelite.client.plugins.oneclickutils.OneClickUtilsPlugin;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import static net.runelite.api.ItemID.*;

@Extension
@PluginDescriptor(
	name = "BD Blast Furnace",
	description = "BD Blast Furnace"
)

@Slf4j
@PluginDependency(OneClickUtilsPlugin.class)
public class BDBlastFurnacePlugin extends Plugin
{
	@Inject
	private BDBlastFurnaceConfig config;
	@Inject
	private OneClickUtilsPlugin oneClickUtilsPlugin;
	@Inject
	private ConfigManager configManager;
	@Inject
	private Client client;
	@Inject
	private InventoryUtils inventory;
	@Inject
	private ObjectUtils objectUtils;
	@Inject
	private BankUtils bankUtils;

	@Provides
	BDBlastFurnaceConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(BDBlastFurnaceConfig.class);
	}

	private Queue<LegacyMenuEntry> actionQueue = new LinkedList<LegacyMenuEntry>();
	private static final int CONVEYOR_ID = 9100;
	private static final int DISPENSER_ID = 9092;
	private static final int DISPENSER_WIDGET_ID = 17694734;
	private static final int BANK_INTERACTION_PARAM1 = 983043;
	private boolean coalBagIsEmpty = true;
	private int barID;
	private int oreID;
	private int coalThreshold;
	private boolean bringCoal = false;
	private boolean expectingBarsToBeMade = false;
	private static final WorldArea bfArea = new WorldArea(1933,4956,30,30,0);
	private static final WorldPoint nextToConveyor = new WorldPoint(1942, 4967, 0);
	private static final WorldPoint nextToDispenser = new WorldPoint(1940,4962,0);
	private int globalTimeout = 0;
	private int dumpCount = 0;
	private int expectedDumpCount = 1;
	private long timeOfLastStaminaSip = 0;
	String menuText = "BD One Click Blast Furnace";

	@Override
	protected void startUp() {
		timeOfLastStaminaSip = System.currentTimeMillis();
		actionQueue.clear();
		setBars();
	}

	@Override
	protected void shutDown() {
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if(event.getGroup().equals("BDOneClickBF")) {
			setBars();
		}
	}


	@Subscribe
	private void onClientTick(ClientTick event) {
		if(bfArea.contains(client.getLocalPlayer().getWorldLocation())){
			client.insertMenuItem(menuText,"",MenuAction.UNKNOWN.getId(),0,0,0,false);
			client.setTempMenuEntry(Arrays.stream(client.getMenuEntries()).filter(x->x.getOption().equals(menuText)).findFirst().orElse(null));
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (event.getMenuOption().contains(menuText)){
			handleClick(event);
			log.info(event.getMenuOption() + ", "
					+ event.getMenuTarget() + ", "
					+ event.getId() + ", "
					+ event.getMenuAction().name() + ", "
					+ event.getParam0() + ", "
					+ event.getParam1());
		}
	}

	private void handleClick(MenuOptionClicked event) {
		//Check if something is blocking
		if (globalTimeout > 0){
			//log.info("Timeout was " + globalTimeout);
			event.consume();
			return;
		}

		//Handle annoying timing dependant actions uniquely
		if (inventory.containsItem(STAMINA_POTION1)){
			if (bankUtils.isOpen()){
				event.setMenuEntry(oneClickUtilsPlugin.drinkPotionFromBank(STAMINA_POTION1));
			}else{
				event.setMenuEntry(oneClickUtilsPlugin.eatFood());
			}
			timeOfLastStaminaSip = System.currentTimeMillis();
			return;
		}

		if(actionQueue.isEmpty()) {
			int dispenserState = client.getVar(Varbits.BAR_DISPENSER);

			//special case a situation where you got out of sync and there are too many bars already made but you still have ores
			if ((dispenserState == 2 || dispenserState == 3) && shouldPickUp() && !coalBagIsEmpty){
				if (inventory.containsItem(COAL)){
					oneClickUtilsPlugin.sanitizeEnqueue(fillCoalBag(), actionQueue, "Couldn't fill coal bag to make room for bars");
				} else if (client.getWidget(DISPENSER_WIDGET_ID) != null){
					oneClickUtilsPlugin.sanitizeEnqueue(takeBarsWidget(), actionQueue, "Couldn't take bars via widget");
					oneClickUtilsPlugin.sanitizeEnqueue(this.openBank(), actionQueue, "Couldn't open bank");
				}else{
					oneClickUtilsPlugin.sanitizeEnqueue(takeFromDispenser(), actionQueue, "Could not find dispenser");
				}
			}


			//if bank is not open and you have ores
			else if(!bankUtils.isOpen() && (inventory.containsItem(oreID) || inventory.containsItem(COAL)) || (config.doGold() &&inventory.containsItem(GOLD_ORE))){
				oneClickUtilsPlugin.sanitizeEnqueue(depositToConveyor(-1), actionQueue, "Couldn't load conveyor");
			}

			//if bank is open, run bank sequence
			else if (bankUtils.isOpen()){
				dumpCount = 0;
				//Deposit bars
				if(inventory.containsItem(barID)){
					oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.depositAllOfItem(barID), actionQueue, "Couldn't desposit bars");
				}

				if (inventory.containsItem(GOLD_BAR)){
					oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.depositAllOfItem(GOLD_BAR), actionQueue, "Couldn't desposit gold bars");
				}

				//Withdraw stamina
				if ((client.getVar(Varbits.RUN_SLOWED_DEPLETION_ACTIVE) == 0 && client.getEnergy() <= 75) ||  (System.currentTimeMillis()-timeOfLastStaminaSip)/1000 > config.staminaPeriod()){
					oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.withdrawItemAmount(STAMINA_POTION1,1,-1), actionQueue, "Couldn't withdraw 1 dose stamina");
				}

				//If using coal, fill up coal bag
				if (bringCoal){
					oneClickUtilsPlugin.sanitizeEnqueue(fillCoalBagFromBank(), actionQueue, "Couldn't fill coal bag from bank");
				}

				//There is enough coal in the machine, bring ores, otherwise bring coal/gold
				if (client.getVar(Varbits.BLAST_FURNACE_COAL) >= coalThreshold || !bringCoal){
					oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.withdrawAllItem(oreID), actionQueue, "Couldn't withdraw ores");
					expectingBarsToBeMade = true;
				}else{
					if (config.doGold()){
						oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.withdrawAllItem(GOLD_ORE), actionQueue, "Couldn't withdraw ores");
						expectingBarsToBeMade = true;
					}else{
						oneClickUtilsPlugin.sanitizeEnqueue(oneClickUtilsPlugin.withdrawAllItem(COAL), actionQueue, "Couldn't withdraw ores");
						expectingBarsToBeMade = false;
					}
				}


				//Go to conveyor
				oneClickUtilsPlugin.sanitizeEnqueue(depositToConveyor(-1), actionQueue, "Couldn't load conveyor");
			}

			//if inventory is full && have bars && bank isnt open, open bank
			else if ((inventory.containsItem(barID) || inventory.containsItem(GOLD_BAR)) && !bankUtils.isOpen()){
				oneClickUtilsPlugin.sanitizeEnqueue(this.openBank(), actionQueue, "Couldn't open bank");
			}

			//if by the conveyor and coal bag is not empty
			else if (client.getLocalPlayer().getWorldLocation().equals(nextToConveyor) && !coalBagIsEmpty){
				oneClickUtilsPlugin.sanitizeEnqueue(emptyCoalBag(), actionQueue, "Couldn't empty coal bag");
				oneClickUtilsPlugin.sanitizeEnqueue(depositToConveyor(1), actionQueue, "Couldn't load conveyor");
			}

			//if dispenser has bars ready
			else if ((dispenserState == 2 || dispenserState == 3  && !inventory.isFull()) && (shouldPickUp() || expectingBarsToBeMade) &&
					client.getLocalPlayer().getWorldLocation().equals(nextToDispenser)){
				if (client.getWidget(DISPENSER_WIDGET_ID) != null){
					oneClickUtilsPlugin.sanitizeEnqueue(takeBarsWidget(), actionQueue, "Couldn't take bars via widget");
					oneClickUtilsPlugin.sanitizeEnqueue(this.openBank(), actionQueue, "Couldn't open bank");
				}else{
					oneClickUtilsPlugin.sanitizeEnqueue(takeFromDispenser(), actionQueue, "Could not find dispenser");
				}
			}

			//if inventory is just a coal bag and coal bag is empty, walk to the dispenser
			else if(coalBagIsEmpty && inventory.containsItemAmount(COAL_BAG_12019, 1, false, true)){
				if(expectingBarsToBeMade){
					event.consume();
					LocalPoint point = LocalPoint.fromWorld(client, nextToDispenser);
					oneClickUtilsPlugin.walkTile(point.getSceneX(), point.getSceneY());
					return;
				}else{
					oneClickUtilsPlugin.sanitizeEnqueue(this.openBank(), actionQueue, "Couldn't open bank");
				}
			}
		}
		if(!actionQueue.isEmpty()){
			//log.info(actionQueue.toString());
			if(actionQueue.peek().getPostActionTickDelay() > 0){
				globalTimeout = actionQueue.peek().getPostActionTickDelay();
			}
			event.setMenuEntry(actionQueue.poll());
			return;
		}

		log.info("Got to end of handle click @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
	}

	@Subscribe
	private void onGameTick(GameTick gameTick) {
		if(globalTimeout > 0){
			//log.info("Gamnetick timeout was " + globalTimeout);
			globalTimeout--;
		}
	}

	private void setBars() {
		int thresholdMultiplier = 27;
		bringCoal = false;
		switch (config.barType()){
			case SILVER:
				coalThreshold = 0;
				barID = SILVER_BAR;
				oreID = SILVER_ORE;
				break;
			case GOLD:
				coalThreshold = 0;
				barID = GOLD_BAR;
				oreID = GOLD_ORE;
				break;
			case IRON:
				coalThreshold = 0;
				barID = IRON_BAR;
				oreID = IRON_ORE;
				break;
			case STEEL:
				bringCoal = true;
				coalThreshold = thresholdMultiplier+thresholdMultiplier*0;
				barID = STEEL_BAR;
				oreID = IRON_ORE;
				break;
			case MITHRIL:
				coalThreshold = thresholdMultiplier+thresholdMultiplier*1;
				bringCoal = true;
				barID = MITHRIL_BAR;
				oreID = MITHRIL_ORE;
				break;
			case ADAMANTITE:
				coalThreshold = thresholdMultiplier+thresholdMultiplier*2;
				bringCoal = true;
				barID = ADAMANTITE_BAR;
				oreID = ADAMANTITE_ORE;
				break;
			case RUNITE:
				coalThreshold = thresholdMultiplier+thresholdMultiplier*3;
				bringCoal = true;
				barID = RUNITE_BAR;
				oreID = RUNITE_ORE;
				break;
		}
	}


	private LegacyMenuEntry depositToConveyor(int afterActionTickDelay){
		expectedDumpCount = 1;
		if(bringCoal){
			expectedDumpCount++;
		}
		if(oneClickUtilsPlugin.isItemEquipped(Set.of(MAX_CAPE_13342))){
			expectedDumpCount++;
		}
		GameObject conveyor = oneClickUtilsPlugin.getGameObject(CONVEYOR_ID);
		if(conveyor != null){
			return new LegacyMenuEntry("Put ore on",
					"Conveyor",
					conveyor.getId(),
					MenuAction.GAME_OBJECT_FIRST_OPTION,
					oneClickUtilsPlugin.getObjectParam0(conveyor),
					oneClickUtilsPlugin.getObjectParam1(conveyor),
					false,
					afterActionTickDelay);
		}
		return null;
	}

	private LegacyMenuEntry takeFromDispenser(){
		GameObject dispenser = oneClickUtilsPlugin.getGameObject(DISPENSER_ID);
		if (dispenser != null){
			return new LegacyMenuEntry("Take",
					"Bar dispenser",
					dispenser.getId(),
					MenuAction.GAME_OBJECT_FIRST_OPTION,
					oneClickUtilsPlugin.getObjectParam0(dispenser),
					oneClickUtilsPlugin.getObjectParam1(dispenser),
					false);
		}
		return null;
	}

	private LegacyMenuEntry openBank(){
		GameObject bankTarget = objectUtils.findNearestBank();
		if (bankTarget != null) {
			return new LegacyMenuEntry("Open",
					"Bank",
					bankTarget.getId(),
					MenuAction.GAME_OBJECT_FIRST_OPTION,
					bankTarget.getSceneMinLocation().getX(),
					bankTarget.getSceneMinLocation().getY(),
					false);
		}
		return null;
	}

	private LegacyMenuEntry takeBarsWidget(){
		if (client.getWidget(DISPENSER_WIDGET_ID) != null){
			return new LegacyMenuEntry("Take",
					"Bars",
					1,
					MenuAction.CC_OP,
					-1,
					DISPENSER_WIDGET_ID,
					false,
					0);
		}
		return null;
	}


	private LegacyMenuEntry fillCoalBagFromBank(){
		WidgetItem coalBagWidget = oneClickUtilsPlugin.getWidgetItem(COAL_BAG_12019);
		if(coalBagWidget != null){
			return new LegacyMenuEntry( "Fill",
					"Coal Bag",
					9,
					MenuAction.CC_OP,
					coalBagWidget.getIndex(),
					BANK_INTERACTION_PARAM1,
					false);
		}
		return null;
	}
	private LegacyMenuEntry fillCoalBag(){
		WidgetItem coalBagWidget = oneClickUtilsPlugin.getWidgetItem(COAL_BAG_12019);
		if(coalBagWidget != null){
			return new LegacyMenuEntry( "Fill",
					"Coal Bag",
					COAL_BAG_12019,
					MenuAction.ITEM_FIRST_OPTION,
					coalBagWidget.getIndex(),
					9764864,
					false);
		}
		return null;
	}

	private LegacyMenuEntry emptyCoalBag(){
		WidgetItem coalBagWidget = oneClickUtilsPlugin.getWidgetItem(COAL_BAG_12019);
		if(coalBagWidget != null){
			return new LegacyMenuEntry( "Empty",
					"Coal Bag",
					coalBagWidget.getId(),
					MenuAction.ITEM_FOURTH_OPTION,
					coalBagWidget.getIndex(),
					WidgetInfo.INVENTORY.getId(),
					false);
		}
		return null;
	}

	private boolean shouldPickUp(){
		int totalBars =
						client.getVar(Varbits.BLAST_FURNACE_BRONZE_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_IRON_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_STEEL_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_MITHRIL_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_ADAMANTITE_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_RUNITE_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_SILVER_BAR) +
						client.getVar(Varbits.BLAST_FURNACE_GOLD_BAR);
		return totalBars > 27;
	}

	@Subscribe
	private void onChatMessage(ChatMessage event){
		if(event.getMessage().contains("All your ore goes onto the")){
			dumpCount++;
			//log.info("Dump count: " + dumpCount);
			if (dumpCount == expectedDumpCount){
				coalBagIsEmpty = true;
			}
		}
		if (event.getMessage().contains("The coal bag is now empty")){
			coalBagIsEmpty = true;
		}
		if (event.getMessage().contains("The coal bag contains 36") && oneClickUtilsPlugin.isItemEquipped(Set.of(MAX_CAPE_13342))){
			coalBagIsEmpty = false;
		}
		if (event.getMessage().contains("The coal bag contains 27") && !oneClickUtilsPlugin.isItemEquipped(Set.of(MAX_CAPE_13342))){
			coalBagIsEmpty = false;
		}
	}



}
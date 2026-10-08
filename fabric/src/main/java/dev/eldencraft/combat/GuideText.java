package dev.eldencraft.combat;

import java.util.List;

/** The pages of the field guide: plain text, so they can be tested without the game. */
public final class GuideText {
	public static final List<String> PAGES = List.of(
		"EldenCraft\nField Guide\n\nYou play as a Minecraft character in Elden Ring's world. Elden Ring still runs the enemies, bosses, graces and quests.\n\nType /guide for another copy.",
		"CONTROLS\nF8  Minecraft pause menu (options, GUI scale)\nF9  camera on/off\nF5  camera modes\nF10 hand mouse and keys to Elden Ring\nR  interact: doors, levers, graces, talk\nEscape  Elden Ring's menu",
		"GATHERING\nYou start with nothing. Pick up logs and loose stone by hand; larger rocks need a pickaxe.\n\nThe Limgrave and Morne tunnels hold iron, copper and coal.\n\nDeposits come back when you rest at a grace.",
		"DYING\nYou keep your things but lose your experience levels. They wait in a pile where you fell, with sparkles. Walk back to take them.\n\nDie again first and that pile is gone.",
		"EXPERIENCE\nExperience is your runes. Kills give it straight away, more for enemies that pay more runes. Mending gear is repaired first.\n\nIt pays merchants, levelling and the anvil.\n\nEnemies drop Elden Ring's own loot: pick it up with R.",
		"MERCHANTS\nMerchants are villagers. Right-click one for its Elden Ring stock at Elden Ring's prices, paid in experience. Limited stock does not come back.\n\nBeside Hewg, R opens an anvil. Beside Roderika, R opens her shop. Shift+R talks to them.",
		"ARMOUR SETS\nBoss armour comes in 17 sets. Two pieces switch on a passive; all four raise every ward you wear by one level.\n\nThe tooltip names the set, and chat tells you when a bonus starts.",
		"WARDS AND STATUS\nWards on armour cut an element's damage or a status's buildup. The status list shows what is building on you.\n\nPlay offline: never take the modded game online.",
		"ELDEN RING ITEMS\nWhat your Elden Ring character picks up, buys or wins appears in your inventory as the real item: weapons, armour, talismans, spells and goods.\n\nGauntlets go in the boots slot.",
		"ATTRIBUTES\nElden Ring's eight attributes: Vigor sets HP (the red bar), Mind FP (blue), Endurance stamina (green) and equip load.\n\nAttacks, sprinting and blocking use stamina.\n\nRest at a grace, then /levelup. /erstats lists them.",
		"WEAPONS\nElden Ring weapons hit with their real attack rating, scaling and requirements. An empty off hand two-hands.\n\nUpgrade them at Hewg's anvil with smithing stones.",
		"FLASKS AND SPELLS\nRight-click a flask to drink. Golden Seeds and Sacred Tears strengthen them; /flasks allocates.\n\nRight-click a spell to memorise it, then a staff or seal to cast. Sneak to switch.\n\n/talismans equips talismans."
	);

	private GuideText() {
	}
}

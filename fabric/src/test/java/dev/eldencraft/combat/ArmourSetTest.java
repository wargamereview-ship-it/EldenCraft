package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArmourSetTest {
	@Test
	void everySetInTheLootDataHasOnePassiveWithTheSameTrimPattern() throws Exception {
		var rules = JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/eldencraft/loot_rules.json"))).getAsJsonObject();
		Map<String, String> patternBySet = new HashMap<>();
		for (var boss : rules.getAsJsonArray("bosses")) {
			var reward = boss.getAsJsonObject().getAsJsonObject("reward");
			if (reward.has("trim")) {
				patternBySet.put(reward.get("set").getAsString(), reward.getAsJsonObject("trim").get("pattern").getAsString());
			}
		}
		assertEquals(17, patternBySet.size());
		assertEquals(patternBySet.size(), ArmourSet.values().length);
		for (var entry : patternBySet.entrySet()) {
			ArmourSet set = ArmourSet.byPattern(entry.getValue());
			assertNotNull(set, entry.getKey());
			assertEquals(entry.getKey(), set.title);
		}
	}

	@Test
	void patternsAreUniqueAndEveryPassiveDoesSomething() {
		var seen = new HashSet<String>();
		for (ArmourSet set : ArmourSet.values()) {
			assertTrue(seen.add(set.pattern), set.title + " shares a trim pattern");
			assertTrue(!set.attributes.isEmpty() || !set.effects.isEmpty() || set.special != ArmourSet.Special.NONE, set.title + " does nothing");
			assertTrue(set.lore().contains(set.title));
		}
		assertNull(ArmourSet.byPattern("nonexistent"));
	}
}

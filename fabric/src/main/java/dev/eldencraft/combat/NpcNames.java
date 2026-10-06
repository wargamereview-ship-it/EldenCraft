package dev.eldencraft.combat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real enemy names. The bridge labels an actor "Enemy c4311 n43111110": its model number and its
 * NpcParam row id. The row id is looked up in {@code npc_names.tsv} (tools/generate_npc_names.py);
 * without a row the label falls back to the model number, as before.
 */
public final class NpcNames {
	private static final Pattern ROW = Pattern.compile(" n(\\d+)\\b");
	private static Map<Integer, String> names;

	private NpcNames() {
	}

	/** The name to show for a bridge label. */
	public static String display(String label) {
		if (label == null || label.isEmpty()) {
			return "Enemy";
		}
		Matcher m = ROW.matcher(label);
		if (!m.find()) {
			return label;
		}
		String name = table().get(parse(m.group(1)));
		return name != null ? name : label.substring(0, m.start()) + label.substring(m.end());
	}

	private static int parse(String digits) {
		try {
			return Integer.parseInt(digits);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static synchronized Map<Integer, String> table() {
		if (names == null) {
			Map<Integer, String> out = new HashMap<>();
			try (var in = NpcNames.class.getResourceAsStream("/data/eldencraft/npc_names.tsv")) {
				if (in != null) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
					for (String line; (line = reader.readLine()) != null; ) {
						int tab = line.indexOf('\t');
						if (tab > 0 && !line.startsWith("#")) {
							out.put(parse(line.substring(0, tab)), line.substring(tab + 1));
						}
					}
				}
			} catch (java.io.IOException e) {
				// A missing table only costs the nicer names.
			}
			names = out;
		}
		return names;
	}
}

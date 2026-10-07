package dev.eldencraft.combat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FieldGuideTest {
	@Test
	void everyPageFitsABookPageAndNamesTheKeysItTeaches() {
		for (String page : GuideText.PAGES) {
			// A book page holds about 14 lines of 19 characters; stay well inside it.
			assertTrue(page.length() <= 330, "page too long: " + page.length());
			assertTrue(page.lines().count() <= 14, "too many lines");
		}
		String all = String.join(" ", GuideText.PAGES);
		for (String key : new String[] { "F8", "F9", "F5", "F10", "Shift+R", "Hewg", "Roderika" }) {
			assertTrue(all.contains(key), key + " is not in the guide");
		}
	}
}

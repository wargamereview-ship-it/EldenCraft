package dev.eldencraft.combat;

import java.util.List;

/** Saved delivery counts remain authoritative after a partial reward stack is used or moved. */
final class RewardProgress {
	private RewardProgress() {}
	static int count(List<String> receipts,String token) {
		int count=0;
		for(String value:receipts) {
			if(value.startsWith(token+"#")) count=Math.max(count,Integer.parseInt(value.substring(token.length()+1)));
		}
		return count;
	}
}

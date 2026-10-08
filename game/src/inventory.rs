//! Mirrors what the Elden Ring character receives into Minecraft. Twice a second the inventory (key items and
//! normal items, with weapons' upgrade and infusion in their ids) is compared with the last look; every item whose
//! count went up is sent to Minecraft as IN_ER_ITEM, which gives the matching Minecraft item. The first look at a
//! character only learns what it already has, so a character's old inventory is not handed over again.

use std::collections::HashMap;
use std::time::{Duration, Instant};

use eldenring::cs::PlayerIns;

use crate::link::Link;
use crate::log;
use crate::proto::{self, InputEvent};

const EVERY: Duration = Duration::from_millis(500);

#[derive(Default)]
pub struct Inventory {
	profile: u32,
	counts: HashMap<u32, u32>,
	last: Option<Instant>,
}

impl Inventory {
	pub fn frame(&mut self, link: &Link, player: &PlayerIns, profile: u32) {
		if profile == 0 || self.last.is_some_and(|t| t.elapsed() < EVERY) {
			return;
		}
		self.last = Some(Instant::now());
		let data = unsafe { player.player_game_data.as_ref() };
		let mut now: HashMap<u32, u32> = HashMap::new();
		for entry in data.equipment.equip_inventory_data.items_data.items() {
			if entry.quantity > 0 && entry.quantity < 10_000 {
				*now.entry(entry.item_id.into_inner()).or_default() += entry.quantity;
			}
		}
		if now.is_empty() {
			return; // still loading
		}
		if profile != self.profile {
			self.profile = profile;
			self.counts = now;
			// The class the character started as (CharaInitParam 3000 + archetype), for its Minecraft attributes.
			let class = u32::from(data.archetype).min(9);
			link.send_input_full(proto::IN_ER_CLASS, 0, 3000 + class as i32, 0, 0);
			log::line(&format!("inventory: character {profile:#x} has {} items; class {}", self.counts.len(), 3000 + class));
			return;
		}
		let mut gains = Vec::new();
		for (&id, &count) in &now {
			let before = self.counts.get(&id).copied().unwrap_or(0);
			if count > before {
				gains.push((id, count - before));
			}
		}
		for (id, gain) in gains {
			let category = (id >> 28) as u16;
			let param = (id & 0x0FFF_FFFF) as i32;
			let event = InputEvent { kind: proto::IN_ER_ITEM, code: category, a: param, b: gain as i32, c: 0 };
			if !link.send_inputs(&[event]) {
				break; // Minecraft has not caught up: try the rest next time
			}
			self.counts.insert(id, self.counts.get(&id).copied().unwrap_or(0) + gain);
			log::line(&format!("inventory: received category {category} item {param} x{gain}"));
		}
		// Items used up or dropped lower the count, so the same item found again is a gain again.
		for (id, count) in self.counts.iter_mut() {
			let have = now.get(id).copied().unwrap_or(0);
			if have < *count {
				*count = have;
			}
		}
	}
}

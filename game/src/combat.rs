//! Minecraft owns player life; weapon damage reaches ER on the safe character frame task.
//! HP uses bound SDK fields; accepted hits also enter the verified native AI notice path.
//! Native defence, poise and physical hit reactions remain a later bridge.

use std::collections::{HashMap, HashSet, VecDeque};
use std::ptr::NonNull;

use eldenring::cs::{CSChrDataModule, ChrIns, ChrInsExt, ChrType, FieldInsHandle, PlayerIns, WorldChrMan};

use crate::link::{Link, McView};
use crate::{log, proto, world};
use crate::world::Space;

const RANGE: f32 = 64.0;
/// One Minecraft damage point (half a heart) removes this many ER health points.
const HP_PER_DAMAGE: f32 = 25.0;
/// Vanilla melee reach plus tolerance for the two games' tick schedules.
const MELEE_REACH: f64 = 4.25;
const _: () = assert!(std::mem::offset_of!(ChrIns, debug_flags) == 0x538);
// Pinned SDK, corroborated by the installed 2.7.1 PlayerGameData accessors/setters.
const _: () = assert!(std::mem::offset_of!(eldenring::cs::PlayerGameData, resistance_gauges) == 0x9cc);
const _: () = assert!(std::mem::offset_of!(eldenring::cs::PlayerGameData, resistance_gauge_max) == 0x9e8);
const _: () = assert!(std::mem::offset_of!(eldenring::cs::PlayerGameData, proc_status_timers) == 0xa20);
const _: () = assert!(std::mem::offset_of!(eldenring::cs::PlayerGameData, proc_status_timer_max) == 0xa3c);

// ER 2.7.1: private data-module debug byte, bit 0 prevents native HP death without
// disabling hit detection. Layout corroborated by the pinned SDK and TarnishedTool:
// https://github.com/borgCode/TarnishedTool/blob/master/TarnishedTool/Memory/Offsets.cs
const DATA_DEBUG_FLAGS: usize = 0x19b;
const NO_DEATH: u8 = 1;
const _: () = assert!(std::mem::offset_of!(CSChrDataModule, hp) == 0x138);
const _: () = assert!(size_of::<CSChrDataModule>() > DATA_DEBUG_FLAGS);
type Identity = (FieldInsHandle, usize, i32);

struct HealthOwner { body: (usize, usize), original_no_death: bool, expected_hp: i32 }
/// `shares`: the hit's make-up for Minecraft's element wards, as sent in IN_HURT_ELEMENTS (a, b).
/// `projectile`: an arrow, spell or thrown object (Minecraft's Projectile Protection applies).
struct IncomingHit { damage: f32, actor: u32, origin: Option<[f64; 3]>, shares: Option<(i32, i32)>, projectile: bool }

/// Percentages by damage type from an attack's base values (physical, magic, fire, lightning, holy):
/// magic..holy packed a byte each (low first), and physical. None when the attack deals no typed damage.
fn hit_shares(base: [u16; 5]) -> Option<(i32, i32)> {
	let total: u32 = base.iter().map(|&v| u32::from(v)).sum();
	if total == 0 { return None; }
	let pct = |v: u16| (u32::from(v) * 100 / total) as i32;
	let packed = pct(base[1]) | pct(base[2]) << 8 | pct(base[3]) << 16 | pct(base[4]) << 24;
	Some((packed, pct(base[0])))
}

fn body(player: &PlayerIns) -> (usize, usize) {
	(player as *const PlayerIns as usize, &*player.chr_ins.modules.data as *const CSChrDataModule as usize)
}
fn no_death(player: &mut PlayerIns, enabled: Option<bool>) -> bool {
 // Only this byte is changed, leaving all neighbouring/debug bits intact.
	unsafe {
		let p = (&mut *player.chr_ins.modules.data as *mut CSChrDataModule).cast::<u8>().add(DATA_DEBUG_FLAGS);
		let flags = p.read();
		if let Some(on) = enabled { p.write(if on { flags | NO_DEATH } else { flags & !NO_DEATH }); }
		flags & NO_DEATH != 0
	}
}

pub struct Combat {
	ids: HashMap<Identity, u32>,
	/// Only identities of attackers matched against this frame's live list. No saved pointer
	/// is dereferenced. A real damaging attacker can bypass incomplete team/activity metadata.
	confirmed_attackers: HashSet<Identity>,
	next_id: u32,
	was_active: bool,
	last_count: usize,
	health_owner: Option<HealthOwner>,
	restore_pending: bool,
	life_epoch: u32,
	was_loading: bool,
	death_pending: bool,
	death_saw_loading: bool,
	health_fraction: f32,
	incoming: VecDeque<IncomingHit>,
	/// Published enemies alive last frame: a transition to dead is a death, once per life.
	alive: HashSet<Identity>,
	/// Deaths awaiting room in the input ring (position, identity, death).
	deaths: VecDeque<crate::loot::Reward>,
	boss_rewards: crate::loot::BossRewards,
	/// MC hits through ER's own hit application, once an ER hit has supplied a template.
	native: crate::native_damage::NativeDamage,
	direct_logged: bool,
	statuses: crate::player_status::Monitor,
}

/// Copy this frame's live entries before borrowing the main player. Pointers are used only in
/// the same PostPhysicsSafe callback, never retained across frames or resolved from MC input.
pub fn nearby(world: &WorldChrMan) -> Vec<NonNull<ChrIns>> {
	world.chr_inses_by_distance.iter()
		.filter(|e| e.distance.is_finite() && e.distance <= RANGE)
		.map(|e| e.chr_ins).collect()
}

impl Combat {
	pub fn new() -> Self {
		Self { ids: HashMap::new(), confirmed_attackers: HashSet::new(), next_id: 1, was_active: false, last_count: 0,
			health_owner: None, restore_pending: false, life_epoch: 1, was_loading: true, death_pending: false,
			death_saw_loading: false, health_fraction: 1.0, incoming: VecDeque::new(), alive: HashSet::new(), deaths: VecDeque::new(),
			boss_rewards: crate::loot::BossRewards::new(), native: crate::native_damage::NativeDamage::new(), direct_logged: false,
			statuses: crate::player_status::Monitor::default() }
	}

	pub fn owns_health(&self) -> bool { self.health_owner.is_some() }

	fn release_health(&mut self, player: Option<&mut PlayerIns>) {
		crate::native_hits::disarm();
		if player.is_none() {
			// Loading can temporarily hide a body which will be reused. Keep only its identity
			// and saved flag, and restore it when a matching live body is available again.
			self.restore_pending = self.health_owner.is_some();
			self.incoming.clear();
			return;
		}
		self.restore_pending = false;
		if let Some(owner) = self.health_owner.take() {
			if let Some(player) = player.filter(|p| body(p) == owner.body) {
				no_death(player, Some(owner.original_no_death));
				if player.chr_ins.modules.data.hp > 0 && !player.chr_ins.chr_flags1c5.death_flag() {
					player.chr_ins.modules.data.hp = (self.health_fraction * player.chr_ins.modules.data.max_hp as f32).ceil().max(1.0) as i32;
				}
			}
		}
		self.incoming.clear();
	}

	pub fn clear(&mut self, link: &Link, player: Option<&mut PlayerIns>) {
		self.release_health(player);
		if !self.was_loading { self.life_epoch = self.life_epoch.wrapping_add(1).max(1); }
		self.was_loading = true;
		self.statuses = crate::player_status::Monitor::default();
		if self.death_pending { self.death_saw_loading = true; }
		link.publish_life(self.life_epoch, 0, 0);
		self.ids.clear(); // Keep next_id: late hits cannot address a new actor after respawning.
		self.confirmed_attackers.clear();
		self.alive.clear();
		// Rewards survive loading/respawn; actor and damage events do not.
		self.was_active = false;
		self.last_count = 0;

		link.publish_actors(&[]);
		link.read_events();
	}

 /// Called before movement/death checks, independently of camera, menus or input ownership.
 /// Minecraft's server health is authoritative whenever a live mirror is connected.
	pub fn player_frame(&mut self, link: &Link, player: &mut PlayerIns, space: &Space,
		near: &[NonNull<ChrIns>], connected: bool) {
		self.was_loading = false;
		if connected {
			self.deaths.extend(self.boss_rewards.frame(player, space, near));
			while let Some(batch) = self.deaths.front() {
				if !link.send_inputs(batch) { break; }
				self.deaths.pop_front();
			}
		}
		if self.restore_pending { self.release_health(Some(player)); }
		if self.death_pending {
			self.release_health(Some(player));
			if self.death_saw_loading && player.chr_ins.modules.data.hp > 0 && !player.chr_ins.chr_flags1c5.death_flag() {
				link.publish_life(self.life_epoch, space.world_id, proto::LIFE_ACTIVE | proto::LIFE_RESPAWN);
				if link.read_vitals(self.life_epoch, space.world_id).is_some_and(|v| v.flags & proto::VITALS_DEAD == 0 && v.health > 0.0) {
					self.death_pending = false; self.death_saw_loading = false;
					log::line("life: both players have respawned; Minecraft hearts resume ownership");
				} else { return; }
			} else {
				link.publish_life(self.life_epoch, space.world_id, 0);
				return;
			}
		}
		let alive = !player.chr_ins.chr_flags1c5.death_flag() && (player.chr_ins.modules.data.hp > 0
			|| self.health_owner.as_ref().is_some_and(|o| o.body == body(player)));
		if !connected || !alive {
			self.release_health(Some(player));
			link.publish_life(self.life_epoch, space.world_id, 0);
			return;
		}
		link.publish_life(self.life_epoch, space.world_id, proto::LIFE_ACTIVE);
		let vitals = link.read_vitals(self.life_epoch, space.world_id);
		if vitals.is_some_and(|v| v.flags & proto::VITALS_DEAD != 0 || v.health <= 0.0) {
			self.release_health(Some(player));
   // Minecraft died; allow ER to run its normal death, runes and grace respawn.
			no_death(player, Some(false));
			player.chr_ins.modules.data.hp = 0;
			self.death_pending = true; self.death_saw_loading = false;
			link.publish_life(self.life_epoch, space.world_id, 0);
			log::line("life: Minecraft hearts reached zero; allowing Elden Ring death and grace respawn");
			return;
		}
		// Live PlayerIns only, on PostPhysicsSafe; never cache or write the PlayerGameData pointer.
		let data = unsafe { player.player_game_data.as_ref() };
		if data.is_main_player {
			let status = crate::player_status::snapshot(data.resistance_gauges, data.resistance_gauge_max,
				data.proc_status_timers, data.proc_status_timer_max);
			link.publish_statuses(self.life_epoch, space.world_id, Some(status));
			if let Some(line) = self.statuses.observe(&status) { log::line(&line); }
		} else { link.publish_statuses(self.life_epoch, space.world_id, None); }
		if self.health_owner.as_ref().is_some_and(|o| o.body != body(player)) {
			self.health_owner = None; self.incoming.clear();
		}
		if self.health_owner.is_none() {
			let expected_hp = player.chr_ins.modules.data.hp;
			let original_no_death = no_death(player, Some(true));
			self.health_owner = Some(HealthOwner { body: body(player), original_no_death, expected_hp });
			log::line("life: native HP becomes a hit sensor; Minecraft hearts own health and death");
		}
		if let Some(v) = vitals { self.health_fraction = (v.health / v.max_health).clamp(0.0, 1.0); }
		// Minecraft's status wards, for the damage hook to cut the buildup of hits on the player.
		crate::native_hits::set_status_wards(vitals.map_or(0, |v| v.status_wards));
		let owner = self.health_owner.as_mut().unwrap();
		let hp = player.chr_ins.modules.data.hp;
		let max_hp = player.chr_ins.modules.data.max_hp.max(1);
		// Buff expiry may lower the native HP cap without an attack.
		let lost = owner.expected_hp.min(max_hp).saturating_sub(hp).max(0);
		let captured = crate::native_hits::take(body(player), self.life_epoch);
		let captured_hp = captured.iter().fold(0i32, |sum, hit| sum.saturating_add(hit.lost));
		for hit in &captured {
			// Match identity against this frame's live list before dereferencing an attacker.
			let attacker = near.iter().find(|p| p.as_ptr() as usize == hit.attacker
				&& p.as_ptr() != &player.chr_ins as *const ChrIns as *mut ChrIns).map(|p| unsafe { p.as_ref() });
			let (actor, origin) = attacker.map_or((0, None), |c| {
				let h = c.modules.physics.position;
				let identity = (c.field_ins_handle, c as *const ChrIns as usize, c.npc_param_id);
				let id = self.ids.get(&identity).copied().unwrap_or(0);
				if self.confirmed_attackers.insert(identity) && id == 0 {
					log::line(&format!("combat: unexported native attacker c{} npc {} team {} type {:?} active {} debug {:#x} block {:?}; normal filter {}; observed-attacker filter {}",
						c.character_id, c.npc_param_id, c.team_type, c.chr_type, c.chr_flags1c8.is_active(), c.debug_flags.0, c.block_id,
						exclusion(c, space, false).unwrap_or("accepted; awaiting actor publication"),
						exclusion(c, space, true).unwrap_or("accepted; publishing a target this frame")));
				}
				(id, Some(space.havok_to_mc([h.0, h.1, h.2])))
			});
			let damage = if hp <= 1 && captured.len() == 1 { 20.0 } else { hit.lost as f32 * 20.0 / max_hp as f32 };
			let elements = hit.attack.and_then(|a| a.elements());
			let shares = elements.and_then(hit_shares);
			let projectile = hit.attack.is_some_and(|a| a.projectile());
			if self.incoming.len() < 64 { self.incoming.push_back(IncomingHit { damage, actor, origin, shares, projectile }); }
			else { log::line("life: incoming hit queue full while Minecraft is unresponsive"); }
			// Which attack it was and its base damage by type (physical/magic/fire/lightning/holy).
			let attack = hit.attack.map_or("unknown".to_string(), |a| {
				let table = if a.npc { "AtkParam_Npc" } else { "AtkParam_Pc" };
				match elements {
					Some([p, m, f, l, h]) => format!("{table} {} phys {p} magic {m} fire {f} lightning {l} holy {h}", a.id),
					None => format!("{table} {} (row not found)", a.id),
				}
			});
			log::line(&format!("life: damage call lost {} HP -> {damage:.2} MC damage from actor {actor}, directed {}, attack param {}, attack {attack}{}",
				hit.lost, origin.is_some(), player.chr_ins.modules.action_flag.received_damage_type, if projectile { ", projectile" } else { "" }));
		}
		// Status buildup hits put on the player, and what the wards left of it (names in the assumed order).
		for b in crate::native_hits::take_buildups() {
			let parts: Vec<String> = (0..7).filter(|&i| b.before[i] > 0.0).map(|i| if b.after[i] < b.before[i] {
				format!("{} {:.1} -> {:.1}", crate::native_hits::STATUSES[i], b.before[i], b.after[i])
			} else {
				format!("{} {:.1}", crate::native_hits::STATUSES[i], b.before[i])
			}).collect();
			log::line(&format!("life: status buildup on you: {}", parts.join(", ")));
		}
		// Falls, status ticks and other HP changes outside the attack call remain undirected.
		// Subtract captured losses so no attack is applied twice through the old HP sensor.
		let other = lost.saturating_sub(captured_hp).max(0);
		if other > 0 {
			let damage = if hp <= 1 && captured.is_empty() { 20.0 } else { other as f32 * 20.0 / max_hp as f32 };
			if self.incoming.len() < 64 { self.incoming.push_back(IncomingHit { damage, actor: 0, origin: None, shares: None, projectile: false }); }
			log::line(&format!("life: uncaptured HP change {other} -> {damage:.2} MC damage (no attack origin)"));
		}
		crate::native_hits::arm(body(player), self.life_epoch);
		no_death(player, Some(true));
		player.chr_ins.modules.data.hp = max_hp;
		self.health_owner.as_mut().unwrap().expected_hp = max_hp;
		while let Some(hit) = self.incoming.front() {
			let p = hit.origin.unwrap_or([0.0; 3]).map(|v| (v as f32).to_bits() as i32);
			let sent = link.send_inputs(&[
				proto::InputEvent { kind: proto::IN_HURT_ORIGIN, code: u16::from(hit.origin.is_some()), a: p[0], b: p[1], c: p[2] },
				proto::InputEvent { kind: proto::IN_HURT_ELEMENTS, code: u16::from(hit.shares.is_some()),
					a: hit.shares.map_or(0, |s| s.0), b: hit.shares.map_or(0, |s| s.1), c: 0 },
				proto::InputEvent { kind: proto::IN_HURT,
					code: if hit.projectile { proto::HURT_PROJECTILE } else if hit.origin.is_some() { proto::HURT_MELEE } else { proto::HURT_OTHER },
					a: (hit.damage * 100.0).round().max(1.0) as i32, b: hit.actor as i32, c: self.life_epoch as i32 },
			]);
			if !sent { break; }
			self.incoming.pop_front();
		}
	}

	pub fn frame(&mut self, link: &Link, player: &PlayerIns, space: &Space,
		near: &[NonNull<ChrIns>], mc: Option<&McView>, active: bool) {
		self.native.frame(active || self.was_active);
		let mut seen = HashSet::new();
		let mut live = Vec::new();
		let mut alive_now = HashSet::new();
		let present: HashSet<Identity> = near.iter().map(|p| {
			let c = unsafe { p.as_ref() };
			(c.field_ins_handle, p.as_ptr() as usize, c.npc_param_id)
		}).collect();
		self.confirmed_attackers.retain(|id| present.contains(id));
		let player_ptr = &player.chr_ins as *const ChrIns;
		let player_pos = player.chr_ins.modules.physics.position;
		for &ptr in near {
			if std::ptr::eq(ptr.as_ptr(), player_ptr) { continue; }
			// The game's live list is authoritative, and no character update task is running here.
			let chr = unsafe { ptr.as_ref() };
			let identity = (chr.field_ins_handle, ptr.as_ptr() as usize, chr.npc_param_id);
			// Checked before the hostility filter: a dying body can lose its active flag.
			if (chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag()) && self.alive.remove(&identity) {
				self.enemy_died(chr, player, space);
			}
			let observed_attacker = self.confirmed_attackers.contains(&identity);
			if exclusion(chr, space, observed_attacker).is_some() { continue; }
			let h = chr.modules.physics.position;
			let distance = (h.0 - player_pos.0).hypot(h.2 - player_pos.2);
			if !distance.is_finite() || distance > RANGE || !h.1.is_finite() { continue; }
			if !seen.insert(identity) { continue; }
			let newly_published = !self.ids.contains_key(&identity);
			let id = *self.ids.entry(identity).or_insert_with(|| {
				let id = self.next_id;
				self.next_id = self.next_id.checked_add(1).expect("combat actor IDs exhausted");
				id
			});
			let actor = record(chr, space, id);
			if newly_published {
				log::line(&format!("combat: target {id} c{} npc {} team {} type {:?} active {} size {:.2}x{:.2} at {:?}; observed attacker {observed_attacker}",
					chr.character_id, chr.npc_param_id, chr.team_type, chr.chr_type, chr.chr_flags1c8.is_active(), actor.width, actor.height, actor.pos));
			}
			if actor.flags & proto::ACTOR_DEAD == 0 { alive_now.insert(identity); }
			live.push((ptr, actor));
			if live.len() == proto::MAX_ACTORS { break; }
		}
		// An enemy out of range or unloaded this frame is not a death; it starts a new life.
		self.alive = alive_now;
		while let Some(batch) = self.deaths.front() {
			if !link.send_inputs(batch) { break; }
			self.deaths.pop_front();
		}
		self.ids.retain(|id, _| seen.contains(id));
		let events = link.read_events();
		// Discard events on a mode handoff: stale MC attacks cannot fire as controls change.
		if active && self.was_active {
			if let Some(mc) = mc {
				// Attack reach is measured from the player, including when F5 moves the camera back.
				let eye = [mc.pos[0], mc.pos[1] + mc.eye_height as f64, mc.pos[2]];
				for event in events {
					if event.kind != proto::EV_HIT_ACTOR || !event.a.is_finite() || event.a <= 0.0 { continue; }
					let Some((ptr, actor)) = live.iter_mut().find(|(_, a)| a.id == event.id) else {
						log::line(&format!("combat: dropped MC hit for unpublished/stale actor {}", event.id));
						continue;
					};
					let status = event.flags & proto::HIT_STATUS != 0;
					let ranged = event.flags & (proto::HIT_PROJECTILE | proto::HIT_FIRE | proto::HIT_STATUS) != 0;
					let reach = distance_to_box(eye, actor);
					if !ranged && reach > MELEE_REACH {
						log::line(&format!("combat: dropped melee actor {} c{}; reach {reach:.2} m exceeds {MELEE_REACH:.2} m", actor.id,
							unsafe { ptr.as_ref() }.character_id));
						continue;
					}
					let chr = unsafe { ptr.as_mut() };
					if chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag() { continue; }
					let flags = (event.flags as u16) & 0xff;
					let protection = protection(chr);
					if protection != 0 {
						link.send_input_full(proto::IN_HIT_FEEDBACK, flags | proto::HIT_REJECTED, 0, actor.id as i32, chr.modules.data.hp);
						log::line(&format!("combat: rejected actor {} c{}; protection {protection:#x}, chr flags {:#x}, debug {:#x}, action {:#x}, event {:#x}",
							actor.id, chr.character_id, chr.chr_flags1c5.0, chr.debug_flags.0,
							chr.modules.action_flag.action_modifiers_flags.0, chr.modules.event.flags));
						continue;
					}
					let before = chr.modules.data.hp;
					// A status (a percentage of a boss's health) can be far more than one swing.
					let damage = (event.a.min(if status { 100_000.0 } else { 1000.0 }) * HP_PER_DAMAGE).round().max(1.0) as i32;
					if status {
						// Poison, rot and the burst of a hemorrhage or frostbite: health only, like ER's own
						// status ticks, so no bullet, no flinch and no new alert.
						let after = before.saturating_sub(damage).max(0);
						chr.modules.data.hp = after;
						chr.last_hit_by = player.chr_ins.field_ins_handle;
						*actor = record(chr, space, actor.id);
						log::line(&format!("combat: actor {} c{} status {:.2} -> {} ER HP; {before} -> {after}", actor.id, chr.character_id, event.a, before - after));
						continue;
					}
					// A native bullet carries the hit into ER's own pipeline: guard, poise/stagger,
					// reaction, effects and AI response; MC's damage replaces the bullet's at HP.
					let critical = event.flags & proto::HIT_CRITICAL != 0;
					let (after, aggro) = match self.native.send(chr, &player.chr_ins, damage, event.weapon, critical, event.d) {
						// Feedback reports the expected result; ER applies it a few frames later.
						crate::native_damage::Outcome::Sent => (before.saturating_sub(damage).max(0), "native hit sent"),
						crate::native_damage::Outcome::Unavailable(why) => {
							let after = before.saturating_sub(damage).max(0);
							chr.modules.data.hp = after;
							let aggro = crate::aggro::on_damage(chr, &player.chr_ins, before - after);
							if !self.direct_logged {
								self.direct_logged = true;
								log::line(&format!("native damage: MC hits use the direct HP bridge ({why})"));
							}
							(after, aggro)
						}
					};
					chr.last_hit_by = player.chr_ins.field_ins_handle;
					// Death/loot handling remains owned by ER; do not reset reward flags or force animations.
					*actor = record(chr, space, actor.id);
					link.send_input_full(proto::IN_HIT_FEEDBACK, flags, before - after, actor.id as i32, after);
					log::line(&format!("combat: actor {} c{} weapon {} MC {:.2} -> {} ER HP; {} -> {} flags {:#x}; aggro {}",
						actor.id, chr.character_id, event.weapon, event.a, before - after, before, after, event.flags, aggro));
				}
			}
		}
		// Hits whose bullet never arrived: apply them directly to the still-live target.
		for missed in self.native.resolve() {
			let Some((ptr, actor)) = live.iter_mut().find(|(p, _)| p.as_ptr() as usize == missed.victim) else {
				log::line("native damage: an undelivered hit's target is no longer nearby; dropped");
				continue;
			};
			let chr = unsafe { ptr.as_mut() };
			if chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag() || protection(chr) != 0 { continue; }
			let before = chr.modules.data.hp;
			let after = before.saturating_sub(missed.damage).max(0);
			chr.last_hit_by = player.chr_ins.field_ins_handle;
			chr.modules.data.hp = after;
			let aggro = crate::aggro::on_damage(chr, &player.chr_ins, before - after);
			*actor = record(chr, space, actor.id);
			log::line(&format!("combat: actor {} c{} direct fallback {} ER HP; {before} -> {after}; aggro {aggro}", actor.id, chr.character_id, missed.damage));
		}
		self.was_active = active;
		if live.len() != self.last_count {
			self.last_count = live.len();
			log::line(&format!("combat: {} nearby hostile hitboxes published", live.len()));
		}
		link.publish_actors(&live.iter().map(|(_, a)| *a).collect::<Vec<_>>());
	}
}

impl Combat {
	/// ER's own last-attacker attribution decides MC loot, so ER weapons and MC weapons both
	/// count, while deaths to falls, other enemies or scripts do not. ER drops and runes stay.
	fn enemy_died(&mut self, chr: &ChrIns, player: &PlayerIns, space: &Space) {
		// Known bosses pay on encounter completion, never on an individual phase/add death.
		if crate::loot::known_boss(chr) { return; }
		let boss = chr.team_type == 7;
		let h = chr.modules.physics.position;
		let pos = space.havok_to_mc([h.0, h.1, h.2]);
		let killer = chr.last_hit_by == player.chr_ins.field_ins_handle;
		log::line(&format!("loot: c{} npc {} entity {} max HP {} team {}{} died; {}", chr.character_id, chr.npc_param_id,
			chr.event_entity_id, chr.modules.data.max_hp, chr.team_type, if boss { " (boss)" } else { "" },
			if killer { "the player hit it last: Minecraft loot requested" } else { "not killed by the player: no loot" }));
		if !killer || !pos.iter().all(|v| v.is_finite()) { return; }
		if self.deaths.len() >= 512 {
			log::line("loot: death queue full while Minecraft is unresponsive; dropping this reward");
			return;
		}
		self.deaths.push_back(crate::loot::batch(pos, chr.event_entity_id, space.world_id, chr.npc_param_id,
			i32::from(chr.block_id()) as u32, player.play_region_id, chr.modules.data.max_hp, chr.character_id as u32, boss, 0));
	}
}

fn exclusion(chr: &ChrIns, space: &Space, observed_attacker: bool) -> Option<&'static str> {
	// Damage attribution is stronger evidence of an opponent than team/type/activity metadata.
	// Preserve live-world, HP and disabled/unloaded checks even for a verified attacker.
	if !observed_attacker {
		if !matches!(chr.chr_type, ChrType::Npc | ChrType::BloodyFingerNpc | ChrType::RecusantNpc) { return Some("character type"); }
		if !hostile_team(chr.team_type) { return Some("team"); }
		if !chr.chr_flags1c8.is_active() { return Some("inactive flag"); }
	}
	if chr.debug_flags.character_disabled() { return Some("character disabled"); }
	if chr.debug_flags.disabled_updates() { return Some("updates disabled"); }
	if chr.debug_flags.force_unloaded() { return Some("forced unloaded"); }
	if chr.modules.data.max_hp <= 0 { return Some("no health pool"); }
	let Some(world_id) = world::collision_world(chr.block_id()) else { return Some("no map block"); };
	if world_id != space.world_id { return Some("different map"); }
	None
}

fn hostile_team(team: u8) -> bool {
	// Named hostile TeamType values:
	// https://github.com/borgCode/TarnishedTool/blob/master/TarnishedTool/Enums/ParamEnums/NpcParam/TeamType.cs
	// The enum leaves 48 and 50+ unnamed; they are the infighting enemy factions. NpcParam
	// membership (https://eldenring.fandom.com/wiki/NPC_Teams) lists only ordinary enemies on
	// them, e.g. 48: Godrick/Leyndell/Radahn soldiers, demi-humans, wolves (c4311, c4070 and
	// c4071 confirmed in game). 11 holds the overworld dragons. Friendly NPCs (0, 2, 26, 28),
	// Torrent (10), spirit summons (47), allies (8), animals (5) and objects (30) stay excluded.
	matches!(team, 6 | 7 | 9 | 11 | 13 | 16 | 17 | 18 | 21 | 23 | 24 | 25 | 27 | 29 | 32 | 33
		| 48 | 50 | 51 | 52 | 54 | 55 | 56 | 57 | 58 | 59 | 60 | 61 | 63 | 65 | 66)
}

fn protected(chr: &ChrIns) -> bool {
	protection(chr) != 0
}

fn protection(chr: &ChrIns) -> u32 {
	let flags = chr.modules.action_flag.action_modifiers_flags;
	// The event-module byte is an undocumented aggregate in the pinned SDK. Its bit 1
	// has not been verified as unconditional immunity in this game build.
	// Use named protection states and log each reason independently.
	u32::from(chr.chr_flags1c5.is_invincible())
		| (u32::from(chr.debug_flags.disabled_hit()) << 1)
		| (u32::from(flags.perfect_invincibility()) << 2)
		| (u32::from(flags.invincible_during_throw_attacker()) << 3)
		| (u32::from(flags.invincible_excluding_throw_attacks_defender()) << 4)
}

fn record(chr: &ChrIns, space: &Space, id: u32) -> proto::ActorRecord {
	let physics = &chr.modules.physics;
	let h = physics.position;
	let o = physics.orientation;
	let f = glam::Quat::from(o).mul_vec3(glam::vec3(0.0, 0.0, -1.0));
	let yaw = world::yaw_pitch(world::dir_to_mc([f.x, f.y, f.z])).0;
	let width = if physics.chr_hit_radius.is_finite() && physics.chr_hit_radius > 0.0 {
		(physics.chr_hit_radius * 2.0).clamp(0.3, 12.0)
	} else { 0.6 };
	let height = if physics.chr_hit_height.is_finite() && physics.chr_hit_height > 0.0 {
		physics.chr_hit_height.clamp(0.4, 20.0)
	} else { 1.8 };
	// Model number, then the NpcParam row: the Minecraft side turns the row into the enemy's real name.
	let label = format!("{} c{:04} n{}", if chr.team_type == 7 { "Boss" } else { "Enemy" }, chr.character_id, chr.npc_param_id.max(0));
	let mut name = [0; 24];
	let n = label.len().min(23);
	name[..n].copy_from_slice(&label.as_bytes()[..n]);
	let dead = chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag();
	proto::ActorRecord {
		id, flags: proto::ACTOR_HOSTILE | if dead { proto::ACTOR_DEAD } else { 0 }
			| if protected(chr) { proto::ACTOR_ESSENTIAL } else { 0 },
		pos: space.havok_to_mc([h.0, h.1, h.2]).map(|p| p as f32), yaw,
		width, height, health_frac: (chr.modules.data.hp as f32 / chr.modules.data.max_hp.max(1) as f32).clamp(0.0, 1.0),
		level: (chr.modules.data.max_hp.max(25) / 25).clamp(1, u16::MAX as i32) as u16, pad: 0, name,
	}
}

fn distance_to_box(eye: [f64; 3], actor: &proto::ActorRecord) -> f64 {
	if !eye.iter().all(|p| p.is_finite()) { return f64::INFINITY; }
	let [x, y, z] = actor.pos.map(f64::from);
	let radius = actor.width as f64 / 2.0;
	let min = [x - radius, y, z - radius];
	let max = [x + radius, y + actor.height as f64, z + radius];
	(0..3).map(|i| (eye[i] - eye[i].clamp(min[i], max[i])).powi(2)).sum::<f64>().sqrt()
}

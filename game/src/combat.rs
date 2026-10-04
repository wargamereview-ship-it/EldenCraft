//! Minecraft owns player life; weapon damage reaches ER on the safe character frame task.
//! HP uses bound SDK fields; accepted hits also enter the verified native AI notice path.
//! Native defence, poise and physical hit reactions remain a later bridge.

use std::collections::{HashMap, HashSet, VecDeque};
use std::ptr::NonNull;

use eldenring::cs::{CSChrDataModule, ChrIns, ChrType, FieldInsHandle, PlayerIns, WorldChrMan};

use crate::link::{Link, McView};
use crate::{log, proto, world};
use crate::world::Space;

const RANGE: f32 = 64.0;
/// One Minecraft damage point (half a heart) removes this many ER health points.
const HP_PER_DAMAGE: f32 = 25.0;
/// Vanilla melee reach plus tolerance for the two games' tick schedules.
const MELEE_REACH: f64 = 4.25;
const _: () = assert!(std::mem::offset_of!(ChrIns, debug_flags) == 0x538);

// ER 2.7.1: private data-module debug byte, bit 0 prevents native HP death without
// disabling hit detection. Layout corroborated by the pinned SDK and TarnishedTool:
// https://github.com/borgCode/TarnishedTool/blob/master/TarnishedTool/Memory/Offsets.cs
const DATA_DEBUG_FLAGS: usize = 0x19b;
const NO_DEATH: u8 = 1;
const _: () = assert!(std::mem::offset_of!(CSChrDataModule, hp) == 0x138);
const _: () = assert!(size_of::<CSChrDataModule>() > DATA_DEBUG_FLAGS);
type Identity = (FieldInsHandle, usize, i32);

struct HealthOwner { body: (usize, usize), original_no_death: bool, expected_hp: i32 }
struct IncomingHit { damage: f32, actor: u32, origin: Option<[f64; 3]> }

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
		Self { ids: HashMap::new(), next_id: 1, was_active: false, last_count: 0,
			health_owner: None, restore_pending: false, life_epoch: 1, was_loading: true, death_pending: false,
			death_saw_loading: false, health_fraction: 1.0, incoming: VecDeque::new() }
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
		if self.death_pending { self.death_saw_loading = true; }
		link.publish_life(self.life_epoch, 0, 0);
		self.ids.clear(); // Keep next_id: late hits cannot address a new actor after respawning.
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
			log::line("life: Minecraft hearts reached zero; allowing Elden Ring death and grace respawn");
			return;
		}
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
				let id = self.ids.get(&(c.field_ins_handle, c as *const ChrIns as usize, c.npc_param_id)).copied().unwrap_or(0);
				(id, Some(space.havok_to_mc([h.0, h.1, h.2])))
			});
			let damage = if hp <= 1 && captured.len() == 1 { 20.0 } else { hit.lost as f32 * 20.0 / max_hp as f32 };
			if self.incoming.len() < 64 { self.incoming.push_back(IncomingHit { damage, actor, origin }); }
			else { log::line("life: incoming hit queue full while Minecraft is unresponsive"); }
			log::line(&format!("life: damage call lost {} HP -> {damage:.2} MC damage from actor {actor}, directed {}, attack param {}",
				hit.lost, origin.is_some(), player.chr_ins.modules.action_flag.received_damage_type));
		}
		// Falls, status ticks and other HP changes outside the attack call remain undirected.
		// Subtract captured losses so no attack is applied twice through the old HP sensor.
		let other = lost.saturating_sub(captured_hp).max(0);
		if other > 0 {
			let damage = if hp <= 1 && captured.is_empty() { 20.0 } else { other as f32 * 20.0 / max_hp as f32 };
			if self.incoming.len() < 64 { self.incoming.push_back(IncomingHit { damage, actor: 0, origin: None }); }
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
				proto::InputEvent { kind: proto::IN_HURT, code: if hit.origin.is_some() { 0 } else { 3 },
					a: (hit.damage * 100.0).round().max(1.0) as i32, b: hit.actor as i32, c: self.life_epoch as i32 },
			]);
			if !sent { break; }
			self.incoming.pop_front();
		}
	}

	pub fn frame(&mut self, link: &Link, player: &PlayerIns, space: &Space,
		near: &[NonNull<ChrIns>], mc: Option<&McView>, active: bool) {
		let mut seen = HashSet::new();
		let mut live = Vec::new();
		let player_ptr = &player.chr_ins as *const ChrIns;
		let player_pos = player.chr_ins.modules.physics.position;
		for &ptr in near {
			if std::ptr::eq(ptr.as_ptr(), player_ptr) { continue; }
			// The game's live list is authoritative, and no character update task is running here.
			let chr = unsafe { ptr.as_ref() };
			if !eligible(chr, space) { continue; }
			let h = chr.modules.physics.position;
			let distance = (h.0 - player_pos.0).hypot(h.2 - player_pos.2);
			if !distance.is_finite() || distance > RANGE || !h.1.is_finite() { continue; }
			let identity = (chr.field_ins_handle, ptr.as_ptr() as usize, chr.npc_param_id);
			if !seen.insert(identity) { continue; }
			let id = *self.ids.entry(identity).or_insert_with(|| {
				let id = self.next_id;
				self.next_id = self.next_id.checked_add(1).expect("combat actor IDs exhausted");
				id
			});
			live.push((ptr, record(chr, space, id)));
			if live.len() == proto::MAX_ACTORS { break; }
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
					let Some((ptr, actor)) = live.iter_mut().find(|(_, a)| a.id == event.id) else { continue; };
					let ranged = event.flags & (proto::HIT_PROJECTILE | proto::HIT_FIRE) != 0;
					if !ranged && distance_to_box(eye, actor) > MELEE_REACH { continue; }
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
					let damage = (event.a.min(1000.0) * HP_PER_DAMAGE).round().max(1.0) as i32;
					let after = before.saturating_sub(damage).max(0);
					chr.last_hit_by = player.chr_ins.field_ins_handle;
					chr.modules.data.hp = after;
					let aggro = crate::aggro::on_damage(chr, &player.chr_ins, before - after);
					// Death/loot handling remains owned by ER; do not reset reward flags or force animations.
					*actor = record(chr, space, actor.id);
					link.send_input_full(proto::IN_HIT_FEEDBACK, flags, before - after, actor.id as i32, after);
					log::line(&format!("combat: actor {} c{} weapon {} MC {:.2} -> {} ER HP; {} -> {} flags {:#x}; aggro {}",
						actor.id, chr.character_id, event.weapon, event.a, before - after, before, after, event.flags, aggro));
				}
			}
		}
		self.was_active = active;
		if live.len() != self.last_count {
			self.last_count = live.len();
			log::line(&format!("combat: {} nearby hostile hitboxes published", live.len()));
		}
		link.publish_actors(&live.iter().map(|(_, a)| *a).collect::<Vec<_>>());
	}
}

fn eligible(chr: &ChrIns, space: &Space) -> bool {
	if !matches!(chr.chr_type, ChrType::Npc | ChrType::BloodyFingerNpc | ChrType::RecusantNpc)
		|| !hostile_team(chr.team_type) || !chr.chr_flags1c8.is_active()
		|| chr.debug_flags.character_disabled() || chr.debug_flags.disabled_updates() || chr.debug_flags.force_unloaded()
		|| chr.modules.data.max_hp <= 0 { return false; }
	let block = chr.block_id;
	if i32::from(block) == -1 { return false; }
	let world_id = if block.is_overworld() {
		i32::from(eldenring::cs::BlockId::from_parts(block.area(), 0, 0, 0)) as u32
	} else { i32::from(block) as u32 };
	world_id == space.world_id
}

fn hostile_team(team: u8) -> bool {
	// NPC_PARAM_ST TeamType. Allies, friendly NPCs, neutral actors and spirit summons excluded.
	// https://github.com/borgCode/TarnishedTool/blob/master/TarnishedTool/Enums/ParamEnums/NpcParam/TeamType.cs
	matches!(team, 6 | 7 | 9 | 13 | 16 | 17 | 18 | 21 | 23 | 24 | 25 | 27 | 29 | 32 | 33)
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
	let label = format!("{} c{:04}", if chr.team_type == 7 { "Boss" } else { "Enemy" }, chr.character_id);
	let mut name = [0; 24];
	let n = label.len().min(23);
	name[..n].copy_from_slice(&label.as_bytes()[..n]);
	let dead = chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag();
	proto::ActorRecord {
		id, flags: proto::ACTOR_HOSTILE | if dead { proto::ACTOR_DEAD } else { 0 }
			| if protected(chr) { proto::ACTOR_ESSENTIAL } else { 0 },
		pos: space.havok_to_mc([h.0, h.1, h.2]).map(|p| p as f32), yaw,
		width, height, health_frac: (chr.modules.data.hp as f32 / chr.modules.data.max_hp.max(1) as f32).clamp(0.0, 1.0),
		level: 0, pad: 0, name,
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

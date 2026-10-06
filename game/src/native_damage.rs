//! Minecraft hits as Elden Ring hits. Each accepted MC hit spawns a native, player-owned bullet
//! into its target, so ER runs its whole hit pipeline itself: hit detection, guarding, poise and
//! stagger, the hit-reaction animation, hit effects/sounds and the AI's damage response (and,
//! being a native bullet, the same path co-op sync would use). At the HP stage (0x448910, hooked
//! in `native_hits`) the bullet's own damage is replaced by Minecraft's.
//!
//! The carrier is ER's Ruin Fragment (BulletParam 10176000): the game's own player-thrown
//! "attract attention" projectile. Its attack row (read from the bullet, not assumed) gets the
//! reaction size (`dmg_level`) and poise damage (`atk_super_armor`) of the MC weapon class just
//! before each spawn, in memory only, and its original values are restored whenever Minecraft
//! is not driving, so a real Ruin Fragment thrown in ER mode is unchanged.
//!
//! CSBulletManager::SpawnBullet is 0x3a2cb0 (WW 2.7.1, fingerprinted; the address and spawn
//! layout match fromsoftware-rs and an independent ER/MC bridge). A bullet that has not reached
//! its target within `HIT_WAIT` leaves the damage to the direct HP bridge, so no hit is lost.

use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use eldenring::cs::{AtkParam_Pc, Bullet, CSBulletManager, ChrIns, FieldInsHandle, SoloParamRepository};
use fromsoftware_shared::FromStatic;
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;

use crate::log;

const SPAWN_BULLET: usize = 0x3a2cb0;
/// Ruin Fragment: a player-owned throwable whose impact only draws attention.
const CARRIER_BULLET: u32 = 10176000;
/// Record fields at the HP stage: the damage ER computed, and non-zero when guarded.
const DAMAGE: usize = 0x228;
const GUARDED: usize = 0x258;
/// Bullet travel plus a few frames; after this the direct HP bridge applies the hit instead.
const HIT_WAIT: Duration = Duration::from_millis(600);
const _: () = assert!(std::mem::offset_of!(ChrIns, field_ins_handle) == 0x8);
const _: () = assert!(size_of::<FieldInsHandle>() == 8);

/// CSBulletManager::SpawnBullet: (manager, &out handle, &spawn data, &out error) -> &out handle.
type SpawnBullet = unsafe extern "system" fn(*mut CSBulletManager, *mut u32, *const SpawnData, *mut u32) -> *mut u32;
static SPAWN: OnceLock<SpawnBullet> = OnceLock::new();

/// The game's bullet spawn request (fromsoftware-rs `BulletSpawnData`, 0x110 bytes). Its
/// unnamed vectors at +0x50/+0x60/+0x70 are the right/up/forward basis, +0x80 the position.
#[repr(C, align(16))]
struct SpawnData {
	owner: u64,
	behavior_id: i32,
	magic_id: i32,
	unk10: u32,
	bullet_id: i32,
	goods_id: i32,
	dummy_poly_id: i32,
	target: u64,
	unk28: [u8; 0x28],
	right: [f32; 4],
	up: [f32; 4],
	forward: [f32; 4],
	position: [f32; 4],
	rest: [u8; 0x80],
}
const _: () = assert!(size_of::<SpawnData>() == 0x110);

/// One MC hit waiting for its bullet to reach the HP stage.
struct Pending { victim: usize, damage: i32, since: Instant, applied: Option<(i32, i32, bool)> }
static PENDING: Mutex<Vec<Pending>> = Mutex::new(Vec::new());

fn fingerprint(bytes: &[u8]) -> u64 {
	bytes.iter().fold(0xcbf29ce484222325u64, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x100000001b3))
}

pub fn install() {
	unsafe {
		let base = GetModuleHandleW(std::ptr::null()) as *const u8;
		if base.is_null() { return; }
		// SpawnBullet's prologue through its argument moves and first call.
		if fingerprint(std::slice::from_raw_parts(base.add(SPAWN_BULLET), 0x40)) != 0x0663589fe9aeaa49 {
			log::line("native damage: bullet spawn code differs; MC hits keep the direct HP bridge");
			return;
		}
		let _ = SPAWN.set(std::mem::transmute::<*const u8, SpawnBullet>(base.add(SPAWN_BULLET)));
		log::line("native damage: native bullet spawn verified; MC hits will be delivered as ER hits");
	}
}

/// Called by the HP stage hook (any thread): the first carrier hit on a victim with a waiting
/// MC hit deals Minecraft's damage instead (a guarded hit keeps ER's smaller result).
pub fn substitute_damage(module: *mut u8, hit: *mut u8) {
	if module.is_null() || hit.is_null() { return; }
	let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
	if pending.is_empty() { return; }
	let victim = unsafe { module.add(8).cast::<usize>().read_unaligned() };
	let Some(p) = pending.iter_mut().find(|p| p.victim == victim && p.applied.is_none()) else { return };
	unsafe {
		let field = hit.add(DAMAGE).cast::<i32>();
		let native = field.read_unaligned();
		let guarded = hit.add(GUARDED).read() != 0;
		let applied = if guarded { native.min(p.damage) } else { p.damage };
		field.write_unaligned(applied);
		p.applied = Some((native, applied, guarded));
	}
}

/// Reaction size and poise damage per MC weapon class; critical hits hit one reaction size
/// harder and half again the poise. Small flinches for quick weapons, a medium stagger for axes
/// and the mace, and a large stagger for the mace's falling smash; poise breaks come from
/// repeated hits like ER's own.
///
/// Minecraft's knockback feeds the stagger: `knockback` is the hit's strongest push (0.4 for a plain
/// hit, 0.5 per Knockback level and for a sprint hit). Each 0.5 adds half the poise damage, and two
/// or more steps (Knockback II, or I while sprinting) also hit one reaction size harder.
fn reaction(weapon: u32, critical: bool, knockback: f32) -> (u8, f32) {
	use crate::proto::*;
	let (level, poise) = match weapon {
		WEAPON_BLADE => (1, 15.0),
		WEAPON_AXE => (2, 25.0),
		WEAPON_BLUNT => (1, 20.0),
		WEAPON_PIERCE => (1, 15.0), // trident
		WEAPON_SPEAR => (1, 18.0),
		WEAPON_MACE => (2, 30.0),
		WEAPON_MACE_SMASH => (3, 60.0),
		WEAPON_ARROW => (1, 8.0),
		_ => (1, 8.0),              // unarmed
	};
	let (level, poise) = if critical { ((level + 1).min(3), poise * 1.5) } else { (level, poise) };
	let steps = if knockback.is_finite() { (knockback / 0.5).floor().clamp(0.0, 3.0) } else { 0.0 };
	((level + steps as u8 / 2).min(3), poise * (1.0 + 0.5 * steps))
}

pub struct NativeDamage {
	/// The carrier's attack row and its original (dmg_level, atk_super_armor).
	atk: Option<(u32, u8, f32)>,
	patched: bool,
	logged: u32,
	disabled: Option<&'static str>,
}

pub enum Outcome {
	/// A native hit is on its way; `resolve` reports it (or falls back) within `HIT_WAIT`.
	Sent,
	/// Use the direct HP bridge for this hit.
	Unavailable(&'static str),
}

/// A waiting MC hit the bullet never delivered: the caller applies it directly.
pub struct Missed { pub victim: usize, pub damage: i32 }

impl NativeDamage {
	pub fn new() -> Self {
		Self { atk: None, patched: false, logged: 0, disabled: None }
	}

	fn params() -> Option<&'static mut SoloParamRepository> {
		unsafe { SoloParamRepository::instance_mut() }.ok()
	}

	/// Find the carrier's attack row once its params are loaded.
	fn carrier(&mut self) -> Result<u32, &'static str> {
		if let Some(reason) = self.disabled { return Err(reason); }
		if let Some((id, _, _)) = self.atk { return Ok(id); }
		let repo = Self::params().ok_or("params not loaded")?;
		let Some(bullet) = repo.get::<Bullet>(CARRIER_BULLET) else {
			self.disabled = Some("Ruin Fragment bullet missing from BulletParam");
			return Err("Ruin Fragment bullet missing from BulletParam");
		};
		let id = bullet.atk_id_bullet();
		let Some(atk) = (id > 0).then(|| repo.get::<AtkParam_Pc>(id as u32)).flatten() else {
			self.disabled = Some("Ruin Fragment attack row missing from AtkParam_Pc");
			return Err("Ruin Fragment attack row missing from AtkParam_Pc");
		};
		self.atk = Some((id as u32, atk.dmg_level(), atk.atk_super_armor()));
		log::line(&format!("native damage: carrier BulletParam {CARRIER_BULLET} -> AtkParam_Pc {id} (original reaction {}, poise {:.0})",
			atk.dmg_level(), atk.atk_super_armor()));
		Ok(id as u32)
	}

	fn set_reaction(&mut self, level: u8, poise: f32) {
		let Some((id, _, _)) = self.atk else { return };
		if let Some(row) = Self::params().and_then(|r| r.get_mut::<AtkParam_Pc>(id)) {
			row.set_dmg_level(level);
			row.set_atk_super_armor(poise);
			self.patched = true;
		}
	}

	/// Every frame: restore the carrier's attack whenever Minecraft is not driving.
	pub fn frame(&mut self, driving: bool) {
		if self.patched && !driving {
			if let Some((_, level, poise)) = self.atk {
				self.set_reaction(level, poise);
			}
			self.patched = false;
		}
	}

	/// Send an MC hit of `damage` ER HP from `player` into `victim` as a native bullet.
	pub fn send(&mut self, victim: &ChrIns, player: &ChrIns, damage: i32, weapon: u32, critical: bool, knockback: f32) -> Outcome {
		let Some(spawn) = SPAWN.get().copied() else { return Outcome::Unavailable("bullet spawn unavailable") };
		if let Err(reason) = self.carrier() { return Outcome::Unavailable(reason); }
		let Ok(manager) = (unsafe { CSBulletManager::instance_mut() }) else { return Outcome::Unavailable("no bullet manager") };
		let (level, poise) = reaction(weapon, critical, knockback);
		self.set_reaction(level, poise);

		// Start just outside the target on the player's side and fly into it at chest height.
		let v = victim.modules.physics.position;
		let p = player.modules.physics.position;
		let mut forward = [v.0 - p.0, 0.0, v.2 - p.2];
		let flat = forward[0].hypot(forward[2]);
		if flat < 1e-3 { forward = [0.0, 0.0, 1.0]; } else { forward = [forward[0] / flat, 0.0, forward[2] / flat]; }
		let physics = &victim.modules.physics;
		let height = if physics.chr_hit_height.is_finite() && physics.chr_hit_height > 0.05 { physics.chr_hit_height.min(40.0) } else { 1.8 };
		let radius = if physics.chr_hit_radius.is_finite() && physics.chr_hit_radius > 0.05 { physics.chr_hit_radius.min(15.0) } else { 0.4 };
		let back = radius + 0.6;
		// Left-handed basis, as the game's camera: right = up x forward.
		let right = [forward[2], 0.0, -forward[0]];
		let data = SpawnData {
			owner: unsafe { std::mem::transmute_copy(&player.field_ins_handle) },
			behavior_id: -1, magic_id: -1, unk10: 0, bullet_id: CARRIER_BULLET as i32, goods_id: -1, dummy_poly_id: -1,
			target: u64::MAX,
			unk28: [0; 0x28],
			right: [right[0], right[1], right[2], 0.0],
			up: [0.0, 1.0, 0.0, 0.0],
			forward: [forward[0], 0.0, forward[2], 0.0],
			position: [v.0 - forward[0] * back, v.1 + 0.6 * height, v.2 - forward[2] * back, 1.0],
			rest: [0; 0x80],
		};
		// Register before spawning: the HP stage may run on another thread.
		PENDING.lock().unwrap_or_else(|e| e.into_inner())
			.push(Pending { victim: victim as *const ChrIns as usize, damage, since: Instant::now(), applied: None });
		let (mut handle, mut error) = (u32::MAX, 0u32);
		unsafe { spawn(manager, &mut handle, &data, &mut error) };
		if handle == u32::MAX {
			let victim = victim as *const ChrIns as usize;
			PENDING.lock().unwrap_or_else(|e| e.into_inner()).retain(|p| p.victim != victim || p.applied.is_some());
			log::line(&format!("native damage: bullet spawn failed (error {error}); using the direct HP bridge for this hit"));
			return Outcome::Unavailable("bullet spawn failed");
		}
		Outcome::Sent
	}

	/// Every frame after `send`s: report delivered hits and return undelivered ones.
	pub fn resolve(&mut self) -> Vec<Missed> {
		let mut missed = Vec::new();
		let mut pending = PENDING.lock().unwrap_or_else(|e| e.into_inner());
		pending.retain(|p| {
			if let Some((native, applied, guarded)) = p.applied {
				if self.logged < 8 {
					self.logged += 1;
					log::line(&format!("native damage: ER hit delivered after {} ms: bullet damage {native} -> MC {} applied {applied}{}",
						p.since.elapsed().as_millis(), p.damage, if guarded { " (guarded)" } else { "" }));
				}
				return false;
			}
			if p.since.elapsed() >= HIT_WAIT {
				log::line(&format!("native damage: bullet did not reach its target within {} ms; applying {} HP directly",
					HIT_WAIT.as_millis(), p.damage));
				missed.push(Missed { victim: p.victim, damage: p.damage });
				return false;
			}
			true
		});
		missed
	}
}

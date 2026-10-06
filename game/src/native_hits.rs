//! Observe the real damage call, before ER's death-only `last_hit_by` attribution.
//! No attack data or native hit reactions are changed by this hook.
//!
//! WW 2.7.1: the HP application instruction is corroborated by TarnishedTool's
//! DamageMultiplier resource / DamageApply offset. The enclosing function, five
//! integer arguments and module owner accessor were checked against the installed PE.
//! All three code signatures must match before installing; other builds stay unhooked.
//!
//! The damage record also names the attack: its AtkParam row at +0x40 and the table at +0x44
//! (0 AtkParam_Npc, 1 AtkParam_Pc). The game looks the row up itself with that pair, as param
//! slot (table + 7), and reads the row's `throw_type_id`; slots 7/8 match fromsoftware-rs. The row
//! gives the attack's base damage by type, so a hit's element mix is known.
//!
//! The record also holds the status buildup the hit applies: seven f32 from +0x158, which the HP stage
//! copies into what it passes to the attack's status effects (the loop over the effect ids at +0x74).
//! For the local player they are scaled here first by the status wards Minecraft reports, so a ward
//! cuts buildup from attacks (not from ground such as rot lakes, which applies no attack).

use std::collections::{HashSet, VecDeque};
use std::ffi::c_void;
use std::sync::atomic::{AtomicU32, Ordering::Relaxed};
use std::sync::{Mutex, OnceLock};
use hudhook::mh::{MH_CreateHook, MH_EnableHook, MH_STATUS};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use eldenring::cs::{AtkParam_Npc, AtkParam_Pc, Bullet, SoloParamRepository};
use fromsoftware_shared::FromStatic;
use crate::log;

const APPLY: usize = 0x448910;
const HP_OFFSET: usize = 0x138;
/// The attack's AtkParam row id and its table in the damage record.
const ATK_ID: usize = 0x40;
const ATK_TABLE: usize = 0x44;
/// Status buildup the hit applies, seven f32 in the order of `PlayerGameData`'s resistance gauges
/// (assumed; the log names them so a known status confirms it).
const BUILDUP: usize = 0x158;
pub const STATUSES: [&str; 7] = ["poison", "scarlet rot", "blood loss", "deathblight", "frostbite", "sleep", "madness"];
/// Which buildup each byte of the status ward levels cuts: poison, scarlet rot, blood loss, frostbite.
const WARDED: [usize; 4] = [0, 1, 2, 4];
/// Ward levels from Minecraft (0-7, a byte each), set every frame from the player's vitals.
static STATUS_WARDS: AtomicU32 = AtomicU32::new(0);

pub fn set_status_wards(packed: u32) {
	STATUS_WARDS.store(packed, Relaxed);
}

/// Buildup a hit on the player carried, before and after the wards.
pub struct Buildup { pub before: [f32; 7], pub after: [f32; 7] }
static BUILDUPS: Mutex<VecDeque<Buildup>> = Mutex::new(VecDeque::new());

pub fn take_buildups() -> Vec<Buildup> {
	BUILDUPS.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect()
}

/// Scales a hit's status buildup on the player by the wards (10% a level), in the record itself.
unsafe fn ward_buildup(hit: *mut u8) {
	if hit.is_null() { return; }
	let field = unsafe { hit.add(BUILDUP).cast::<f32>() };
	let before: [f32; 7] = std::array::from_fn(|i| unsafe { field.add(i).read_unaligned() });
	// Anything but small non-negative numbers means this is not the buildup: leave it alone.
	if before.iter().any(|v| !v.is_finite() || *v < 0.0 || *v > 100_000.0) || !before.iter().any(|v| *v > 0.0) { return; }
	let wards = STATUS_WARDS.load(Relaxed);
	let mut after = before;
	for (k, &i) in WARDED.iter().enumerate() {
		let level = ((wards >> (8 * k)) & 0xFF).min(7);
		if level > 0 && after[i] > 0.0 {
			after[i] *= 1.0 - 0.1 * level as f32;
			unsafe { field.add(i).write_unaligned(after[i]); }
		}
	}
	let mut seen = BUILDUPS.lock().unwrap_or_else(|e| e.into_inner());
	if seen.len() < 32 { seen.push_back(Buildup { before, after }); }
}
const MODULES: usize = std::mem::offset_of!(eldenring::cs::ChrIns, modules);
const _: () = assert!(MODULES == 0x190);
type ApplyDamage = unsafe extern "system" fn(*mut u8, *mut u8, *mut u8, u8, u8);
static ORIGINAL: OnceLock<ApplyDamage> = OnceLock::new();

#[derive(Clone, Copy)]
pub struct Hit { pub attacker: usize, pub lost: i32, pub attack: Option<Attack> }

/// The AtkParam row behind a hit: `npc` for AtkParam_Npc, otherwise AtkParam_Pc.
#[derive(Clone, Copy)]
pub struct Attack { pub id: u32, pub npc: bool }

/// The AtkParam rows projectiles use (`BulletParam.atkId_Bullet`): arrows, spells, breath and thrown
/// pots. Built once the params are loaded.
static BULLET_ATTACKS: OnceLock<HashSet<u32>> = OnceLock::new();

impl Attack {
	/// Whether the hit came from a projectile (its attack row is one a bullet uses). Main thread only.
	pub fn projectile(&self) -> bool {
		let set = match BULLET_ATTACKS.get() {
			Some(set) => set,
			None => {
				let Ok(repo) = (unsafe { SoloParamRepository::instance() }) else { return false };
				let set: HashSet<u32> = repo.rows::<Bullet>()
					.filter_map(|(_, b)| u32::try_from(b.atk_id_bullet()).ok().filter(|&id| id > 0)).collect();
				log::line(&format!("life: {} attack rows belong to projectiles (BulletParam)", set.len()));
				BULLET_ATTACKS.get_or_init(|| set)
			}
		};
		set.contains(&self.id)
	}

	/// Base damage by type: physical, magic, fire, lightning, holy. Main thread only.
	pub fn elements(&self) -> Option<[u16; 5]> {
		let repo = unsafe { SoloParamRepository::instance() }.ok()?;
		let row = if self.npc { repo.get::<AtkParam_Npc>(self.id) } else { repo.get::<AtkParam_Pc>(self.id) }?;
		Some([row.atk_phys(), row.atk_mag(), row.atk_fire(), row.atk_thun(), row.atk_dark()])
	}
}

/// The attack a damage record names, when the pair is one the game itself would look up.
unsafe fn attack(hit: *const u8) -> Option<Attack> {
	if hit.is_null() { return None; }
	let id = unsafe { hit.add(ATK_ID).cast::<i32>().read_unaligned() };
	let table = unsafe { hit.add(ATK_TABLE).cast::<i32>().read_unaligned() };
	(id >= 0 && (0..2).contains(&table)).then_some(Attack { id: id as u32, npc: table == 0 })
}
struct Sensor { body: (usize, usize), epoch: u32, hits: VecDeque<Hit> }
static SENSOR: Mutex<Sensor> = Mutex::new(Sensor { body: (0, 0), epoch: 0, hits: VecDeque::new() });

pub fn arm(body: (usize, usize), epoch: u32) {
	let mut s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
	if s.body != body || s.epoch != epoch { s.hits.clear(); }
	s.body = body; s.epoch = epoch;
}
pub fn disarm() {
	let mut s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
	s.body = (0, 0); s.epoch = 0; s.hits.clear();
}
pub fn take(body: (usize, usize), epoch: u32) -> Vec<Hit> {
	let mut s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
	if s.body != body || s.epoch != epoch { return Vec::new(); }
	s.hits.drain(..).collect()
}

/// Follow the live damage module's owner before using its data pointer. The sensor's
/// addresses are identity checks, never permission to dereference a cached data module.
unsafe fn live_hp(module: *mut u8, body: (usize, usize)) -> Option<*const i32> {
	if module.is_null() || body.0 == 0 || body.1 == 0 { return None; }
	let owner = unsafe { module.add(8).cast::<usize>().read_unaligned() };
	if owner != body.0 { return None; }
	let modules = unsafe { (owner as *const u8).add(MODULES).cast::<*const u8>().read_unaligned() };
	if modules.is_null() { return None; }
	let data = unsafe { modules.cast::<usize>().read_unaligned() };
	if data != body.1 { return None; }
	Some(unsafe { (data as *const u8).add(HP_OFFSET).cast::<i32>() })
}

unsafe extern "system" fn apply(module: *mut u8, attacker: *mut u8, hit: *mut u8, flag: u8, extra: u8) {
	let Some(original) = ORIGINAL.get().copied() else { return };
	// A replayed Minecraft hit takes Minecraft's damage at this stage (see native_damage).
	crate::native_damage::substitute_damage(module, hit);
	// Only read the data module of the currently armed local player, on the game's
	// own damage thread while that module is already live. Retain identity only.
	let sensor = if !module.is_null() {
		let owner = unsafe { module.add(8).cast::<usize>().read_unaligned() };
		let s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
		(owner == s.body.0 && s.body.1 != 0).then_some((s.body, s.epoch))
	} else { None };
	let before = sensor.and_then(|(body, _)| unsafe { live_hp(module, body).map(|hp| hp.read_unaligned()) });
	if sensor.is_some() { unsafe { ward_buildup(hit); } }
	unsafe { original(module, attacker, hit, flag, extra); }
	if let (Some((body, epoch)), Some(before)) = (sensor, before) {
		let Some(hp) = (unsafe { live_hp(module, body) }) else { return; };
		let after = unsafe { hp.read_unaligned() };
		let lost = before.saturating_sub(after).max(0);
		if lost > 0 {
			let mut s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
			if s.body == body && s.epoch == epoch && s.hits.len() < 64 {
				s.hits.push_back(Hit { attacker: attacker as usize, lost, attack: unsafe { attack(hit) } });
			}
		}
	}
}

pub fn install() {
	unsafe {
		let base = GetModuleHandleW(std::ptr::null()) as *mut u8;
		if base.is_null() { return; }
		let signatures: &[(usize, &[u8])] = &[
			(APPLY, &[0x4c, 0x8b, 0xdc, 0x55, 0x53, 0x56, 0x57, 0x41, 0x56, 0x41, 0x57, 0x49, 0x8d, 0x6b, 0x88]),
			(0x448a54, &[0x41, 0x8b, 0x96, 0x28, 0x02, 0x00, 0x00]),
			(0x43d250, &[0x48, 0x8b, 0x41, 0x08, 0xc3]),
		];
		if signatures.iter().any(|(at, bytes)| std::slice::from_raw_parts(base.add(*at), bytes.len()) != *bytes) {
			log::line("life: damage call signatures differ; directional native shield bridge unavailable");
			return;
		}
		// Hudhook has already initialized MinHook. Only enable this one new hook.
		let target = base.add(APPLY).cast::<c_void>();
		let mut original = std::ptr::null_mut();
		let status = MH_CreateHook(target, apply as *const () as *mut c_void, &mut original);
		if status != MH_STATUS::MH_OK {
			log::line(&format!("life: damage call hook creation failed: {status:?}")); return;
		}
		let _ = ORIGINAL.set(std::mem::transmute::<*mut c_void, ApplyDamage>(original));
		let status = MH_EnableHook(target);
		if status == MH_STATUS::MH_OK { log::line("life: native damage call observed; attacker direction available without native death"); }
		else { log::line(&format!("life: damage call hook enable failed: {status:?}")); }
	}
}

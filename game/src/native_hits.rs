//! Observe the real damage call, before ER's death-only `last_hit_by` attribution.
//! No attack data or native hit reactions are changed by this hook.
//!
//! WW 2.7.1: the HP application instruction is corroborated by TarnishedTool's
//! DamageMultiplier resource / DamageApply offset. The enclosing function, five
//! integer arguments and module owner accessor were checked against the installed PE.
//! All three code signatures must match before installing; other builds stay unhooked.

use std::collections::VecDeque;
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};
use hudhook::mh::{MH_CreateHook, MH_EnableHook, MH_STATUS};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use crate::log;

const APPLY: usize = 0x448910;
const HP_OFFSET: usize = 0x138;
const MODULES: usize = std::mem::offset_of!(eldenring::cs::ChrIns, modules);
const _: () = assert!(MODULES == 0x190);
type ApplyDamage = unsafe extern "system" fn(*mut u8, *mut u8, *mut u8, u8, u8);
static ORIGINAL: OnceLock<ApplyDamage> = OnceLock::new();

#[derive(Clone, Copy)]
pub struct Hit { pub attacker: usize, pub lost: i32 }
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
	// Only read the data module of the currently armed local player, on the game's
	// own damage thread while that module is already live. Retain identity only.
	let sensor = if !module.is_null() {
		let owner = unsafe { module.add(8).cast::<usize>().read_unaligned() };
		let s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
		(owner == s.body.0 && s.body.1 != 0).then_some((s.body, s.epoch))
	} else { None };
	let before = sensor.and_then(|(body, _)| unsafe { live_hp(module, body).map(|hp| hp.read_unaligned()) });
	unsafe { original(module, attacker, hit, flag, extra); }
	if let (Some((body, epoch)), Some(before)) = (sensor, before) {
		let Some(hp) = (unsafe { live_hp(module, body) }) else { return; };
		let after = unsafe { hp.read_unaligned() };
		let lost = before.saturating_sub(after).max(0);
		if lost > 0 {
			let mut s = SENSOR.lock().unwrap_or_else(|e| e.into_inner());
			if s.body == body && s.epoch == epoch && s.hits.len() < 64 {
				s.hits.push_back(Hit { attacker: attacker as usize, lost });
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

//! Notify local ER AI after accepted Minecraft damage, on PostPhysicsSafe.
//!
//! Installed WW 2.7.1 call chain: damage-module post-hit -> ComManipulator +0x70
//! (0x3ce9a0) -> AI damage notice (0x2c50d0). The latter reports the attacker to
//! the AI's threat/interrupt components and accumulates damage, without applying HP,
//! poise or animation damage again. It consumes only damage +0x228 and three outcome
//! bytes +0x258/+0x259/+0x264. Zero outcomes select an ordinary, unblocked hit.
//! The complete notice functions and their hit-data callees must match the inspected
//! executable before enabling this bridge. No native AI pointers survive a frame.

use std::sync::OnceLock;
use eldenring::cs::{ChrCtrl, ChrIns};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
use crate::log;

const COM_VTABLE: usize = 0x2a2cd70;
const COM_DAMAGE: usize = 0x3ce9a0;
const MANIPULATOR: usize = 0x3f0560;
const AI_DAMAGE: usize = 0x2c50d0;
const MANIPULATOR_OWNER: usize = 0xa8;
const MANIPULATOR_AI: usize = 0xc0;
const _: () = assert!(std::mem::offset_of!(ChrIns, chr_ctrl) == 0x58);
const _: () = assert!(std::mem::offset_of!(ChrCtrl, owner) == 0x10);
const _: () = assert!(std::mem::offset_of!(ChrCtrl, manipulator) == 0x18);

type GetManipulator = unsafe extern "system" fn(*const ChrIns) -> *mut u8;
type NotifyDamage = unsafe extern "system" fn(*mut u8, *const ChrIns, *const AiDamageNotice);
struct Native { base: usize, manipulator: GetManipulator, notify: NotifyDamage }
static NATIVE: OnceLock<Native> = OnceLock::new();

/// This buffer is only consumed by the verified AI notice, never the native damage pipeline.
/// In particular, it contains no attack-param/resource pointers and cannot deal damage twice.
#[repr(C, align(16))]
struct AiDamageNotice([u8; 0x270]);
impl AiDamageNotice {
	fn new(attacker: &ChrIns, lost_hp: i32) -> Self {
		let mut notice = Self([0; 0x270]);
		// The regular ComManipulator entry also carries the attack owner here. Keep the
		// same attribution, although the direct AI entry receives it as its second argument.
		notice.0[0x1d8..0x1e0].copy_from_slice(&(attacker as *const ChrIns as usize).to_le_bytes());
		notice.0[0x228..0x22c].copy_from_slice(&lost_hp.to_le_bytes());
		notice
	}
}

fn fingerprint(bytes: &[u8]) -> u64 {
	bytes.iter().fold(0xcbf29ce484222325u64, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x100000001b3))
}

pub fn install() {
	unsafe {
		let base = GetModuleHandleW(std::ptr::null()) as *const u8;
		if base.is_null() { return; }
		// FNV-1a of complete function bodies (through RET, excluding Arxan padding).
		// These checks cover every direct consumer of the abbreviated AI hit notice,
		// as well as the active-manipulator accessor and ComManipulator AI offset.
		let guards: &[(usize, usize, u64)] = &[
			(0x3f0560, 0x9, 0x8fc5634f0a62f1d0),
			(0x3c6f50, 0x11, 0x769fd387a80ee7a7),
			(0x3ce9a0, 0xe0, 0x80a20a04f4aa11d5),
			(0x2c50d0, 0xd7, 0x1a3df544109e003e),
			(0x3536e0, 0xab, 0x21f69f9bc61e64e1),
			(0x33b4d0, 0x234, 0x46867e42fb850b0b),
			(0x342530, 0xb9, 0xe68cf254e17783d7),
			(0x339600, 0x74, 0x5491ca0e554c0184),
			(0x3453c0, 0x7, 0xe8a8aa11133c95f0),
		];
		for &(rva, len, expected) in guards {
			if fingerprint(std::slice::from_raw_parts(base.add(rva), len)) != expected {
				log::line(&format!("aggro: native code differs at {rva:#x}; AI notice bridge disabled"));
				return;
			}
		}
		if base.add(COM_VTABLE + 0x70).cast::<usize>().read() != base as usize + COM_DAMAGE {
			log::line("aggro: ComManipulator damage slot differs; AI notice bridge disabled");
			return;
		}
		let _ = NATIVE.set(Native {
			base: base as usize,
			manipulator: std::mem::transmute::<*const u8, GetManipulator>(base.add(MANIPULATOR)),
			notify: std::mem::transmute::<*const u8, NotifyDamage>(base.add(AI_DAMAGE)),
		});
		log::line("aggro: native AI damage notice verified; accepted Minecraft hits report the player as attacker");
	}
}

/// Call only for this frame's live, eligible enemy and live local player, after a
/// positive HP change. Respect death and unsupported/network AI rather than guessing a layout.
pub fn on_damage(victim: &ChrIns, attacker: &ChrIns, lost_hp: i32) -> &'static str {
	if lost_hp <= 0 { return "no damage"; }
	if victim.modules.data.hp <= 0 || victim.chr_flags1c5.death_flag() { return "killed"; }
	let Some(native) = NATIVE.get() else { return "bridge unavailable"; };
	let victim_ptr = victim as *const ChrIns;
	if victim.chr_ctrl.owner.as_ptr() as *const ChrIns != victim_ptr { return "controller owner differs"; }
	unsafe {
		let is_com = |p: *mut u8| !p.is_null()
			&& p.cast::<usize>().read_unaligned() == native.base + COM_VTABLE
			&& p.add(MANIPULATOR_OWNER).cast::<*const ChrIns>().read_unaligned() == victim_ptr;
		let mut manipulator = (native.manipulator)(victim_ptr);
		if !is_com(manipulator) {
			// Riding/script control may temporarily override the normal AI manipulator.
			// Its owner and exact ComManipulator vtable still have to match this enemy.
			manipulator = victim.chr_ctrl.manipulator as *mut u8;
			if !is_com(manipulator) { return "no local AI"; }
		}
		let ai = manipulator.add(MANIPULATOR_AI).cast::<*mut u8>().read_unaligned();
		if ai.is_null() { return "AI unloaded"; }
		let notice = AiDamageNotice::new(attacker, lost_hp);
		(native.notify)(ai, attacker, &notice);
	}
	"notified"
}

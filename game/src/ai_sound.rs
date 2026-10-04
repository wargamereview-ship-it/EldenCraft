//! Native AI hearing while Minecraft moves the player.
//!
//! ER enemies notice the player by sight and by AI sounds (AiSoundParam rows), which the player's
//! own locomotion emits through the native AI sound manager. Minecraft-driven movement moves the
//! native body by position requests without those animations, so enemies could only see the
//! player: confirmed in game as detection from behind working in ER mode only.
//!
//! While ER drives, this observes which AiSoundParam IDs the player's own body emits, at what
//! body speed and how often each repeats. While Minecraft drives on the ground, it emits the
//! learned footstep matching the current speed through the game's own character emitter. No ID
//! is invented: nothing is emitted before ER has emitted it for this player. The learned table
//! persists beside the DLL. Sneaking, swimming, flying and air time stay silent.
//!
//! Installed WW 2.7.1 code, every body fingerprinted below before anything is hooked or called:
//! - 0x37a6c0: AI manager add-sound (ai_man, &pos, &emitter, &owner, &param_id). Its only callee
//!   0x34a1b0 is the sole caller-reachable insert into the sound manager (under its lock), so
//!   every AI sound passes here. Emitter +0 kind (0: character), +8 that character's handle.
//! - 0x5f2c50: character emitter (ChrIns*, param_id) -> bool, used by event scripts. It builds a
//!   kind-0 emitter from ChrIns+8 and takes the position from ChrCtrl (+0x58) -> physics. It
//!   uses the AI manager global 0x3d66548 without a null check, so that is checked first.

use std::cell::Cell;
use std::collections::HashMap;
use std::ffi::c_void;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use eldenring::cs::{ChrIns, FieldInsHandle, PlayerIns};
use hudhook::mh::{MH_CreateHook, MH_EnableHook, MH_STATUS};
use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;

use crate::link::McView;
use crate::{log, proto};

const ADD_SOUND: usize = 0x37a6c0;
const CHR_EMIT: usize = 0x5f2c50;
const AI_MAN: usize = 0x3d66548;
const _: () = assert!(std::mem::offset_of!(ChrIns, field_ins_handle) == 0x8);
const _: () = assert!(size_of::<FieldInsHandle>() == 8);
const _: () = assert!(std::mem::offset_of!(ChrIns, chr_ctrl) == 0x58);

/// A footstep candidate must recur while moving, at a footstep-like rate.
const MIN_COUNT: u32 = 4;
const MIN_SPEED: f64 = 1.0;
const MIN_INTERVAL: f64 = 0.15;
const MAX_INTERVAL: f64 = 2.0;
/// Means adapt instead of freezing on the first session's samples.
const MEAN_WINDOW: f64 = 200.0;
const TABLE_FILE: &str = "eldencraft_ai_sounds.txt";

type AddSound = unsafe extern "system" fn(*mut u8, *const u8, *const u8, *const u8, *const i32);
type ChrEmit = unsafe extern "system" fn(*const ChrIns, i32) -> bool;
struct Native { base: usize, add: AddSound, emit: ChrEmit }
static NATIVE: OnceLock<Native> = OnceLock::new();

/// The local player's handle bits; 0 records nothing.
static WATCH: AtomicU64 = AtomicU64::new(0);
#[derive(Clone, Copy)]
struct Heard { id: i32, kind: u32, ours: bool, pos: [f32; 3] }
static HEARD: Mutex<Vec<Heard>> = Mutex::new(Vec::new());
thread_local! { static EMITTING: Cell<bool> = const { Cell::new(false) }; }

fn fingerprint(bytes: &[u8]) -> u64 {
	bytes.iter().fold(0xcbf29ce484222325u64, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x100000001b3))
}

unsafe extern "system" fn add_sound(man: *mut u8, pos: *const u8, emitter: *const u8, owner: *const u8, id: *const i32) {
	let Some(native) = NATIVE.get() else { return };
	let watch = WATCH.load(Ordering::Relaxed);
	if watch != 0 && !EMITTING.with(Cell::get) && !emitter.is_null() && !id.is_null() && !pos.is_null() {
		// Read-only copies of the caller's arguments; nothing is retained past this call.
		let heard = unsafe {
			let kind = emitter.cast::<u32>().read_unaligned();
			let handle = emitter.add(8).cast::<u64>().read_unaligned();
			let p = pos.cast::<[f32; 3]>().read_unaligned();
			Heard { id: id.read_unaligned(), kind, ours: kind == 0 && handle == watch, pos: p }
		};
		let mut list = HEARD.lock().unwrap_or_else(|e| e.into_inner());
		if list.len() < 128 { list.push(heard); }
	}
	unsafe { (native.add)(man, pos, emitter, owner, id) }
}

pub fn install() {
	unsafe {
		let base = GetModuleHandleW(std::ptr::null()) as *mut u8;
		if base.is_null() { return; }
		// FNV-1a of complete function bodies through RET: the add-sound entry and the sound
		// manager insert, the record builder/constructor and their field helpers, the
		// character emitter, its emitter/handle and position helpers, and the ChrCtrl getter.
		let guards: &[(usize, usize, u64)] = &[
			(0x37a6c0, 0x69, 0x2fa8e9373954186a),
			(0x34a1b0, 0x102, 0x57c6261b6ee6cb2e),
			(0x348890, 0x21, 0x59afb0df5620cfe4),
			(0x348760, 0x127, 0x296f58bec8d8b701),
			(0x5f2c50, 0xfe, 0xb931bcd4d6244024),
			(0x348ce0, 0x1a, 0xd651f61cf0eceb9d),
			(0x348d50, 0x2e, 0x39964667ae751373),
			(0x348b00, 0x6, 0xa9928152599947f6),
			(0x348c80, 0x11, 0x6c6e48246c4ff90e),
			(0x348ca0, 0x18, 0x30d53bd86841ec1b),
			(0x3f0f90, 0x44, 0x37164e4a7db859d1),
			(0x3c7000, 0x26, 0x86eb9ba77b8f9931),
		];
		for &(rva, len, expected) in guards {
			if fingerprint(std::slice::from_raw_parts(base.add(rva), len)) != expected {
				log::line(&format!("ai sound: native code differs at {rva:#x}; MC-mode footstep noise disabled"));
				return;
			}
		}
		// Hudhook has already initialized MinHook. Only enable this one new hook.
		let target = base.add(ADD_SOUND).cast::<c_void>();
		let mut original = std::ptr::null_mut();
		let status = MH_CreateHook(target, add_sound as *const () as *mut c_void, &mut original);
		if status != MH_STATUS::MH_OK {
			log::line(&format!("ai sound: hook creation failed: {status:?}"));
			return;
		}
		let _ = NATIVE.set(Native {
			base: base as usize,
			add: std::mem::transmute::<*mut c_void, AddSound>(original),
			emit: std::mem::transmute::<*mut u8, ChrEmit>(base.add(CHR_EMIT)),
		});
		let status = MH_EnableHook(target);
		if status == MH_STATUS::MH_OK {
			log::line("ai sound: native AI sound emitter verified; ER-mode player sounds are learned and replayed as MC-mode footsteps");
		} else {
			log::line(&format!("ai sound: hook enable failed: {status:?}"));
		}
	}
}

/// Stop attributing sounds while no live local player is known (loading, title screen).
pub fn unwatch() {
	WATCH.store(0, Ordering::Relaxed);
	HEARD.lock().unwrap_or_else(|e| e.into_inner()).clear();
}

fn emit(player: &PlayerIns, id: i32) -> bool {
	let Some(native) = NATIVE.get() else { return false };
	// The emitter dereferences this manager unconditionally.
	let man = unsafe { (native.base as *const u8).add(AI_MAN).cast::<usize>().read_volatile() };
	if man == 0 { return false; }
	EMITTING.with(|e| e.set(true));
	let ok = unsafe { (native.emit)(&player.chr_ins, id) };
	EMITTING.with(|e| e.set(false));
	ok
}

#[derive(Clone, Copy, Default)]
struct Stat { count: u32, speed: f64, interval: f64, intervals: u32 }

pub struct Hearing {
	stats: HashMap<i32, Stat>,
	last_heard: HashMap<i32, Instant>,
	last_body: Option<([f64; 3], Instant)>,
	/// Smoothed horizontal body speed, metres per second, measured the same way in both modes.
	speed: f64,
	last_step: Option<Instant>,
	chosen: Option<i32>,
	emitted: u32,
	nearby_other: HashMap<(u32, i32), u32>,
	dirty: bool,
	path: PathBuf,
	next_save: Instant,
	next_report: Instant,
}

impl Hearing {
	pub fn new() -> Self {
		let path = log::beside_dll(TABLE_FILE);
		let mut stats = HashMap::new();
		if let Ok(text) = std::fs::read_to_string(&path) {
			for line in text.lines().filter(|l| !l.starts_with('#')) {
				let v: Vec<f64> = line.split_whitespace().filter_map(|w| w.parse().ok()).collect();
				if let [id, count, speed, interval, intervals] = v[..] {
					if [speed, interval].iter().all(|x| x.is_finite()) {
						stats.insert(id as i32, Stat { count: count as u32, speed, interval, intervals: intervals as u32 });
					}
				}
			}
		}
		log::line(&format!("ai sound: {} learned player sounds loaded from {}", stats.len(), path.display()));
		Self { stats, last_heard: HashMap::new(), last_body: None, speed: 0.0, last_step: None, chosen: None,
			emitted: 0, nearby_other: HashMap::new(), dirty: false, path, next_save: Instant::now(),
			next_report: Instant::now() + Duration::from_secs(10) }
	}

	/// The ER-learned footstep closest to this speed, among the most frequent movement sounds.
	fn footstep(&self, speed: f64) -> Option<(i32, Stat)> {
		let ready: Vec<_> = self.stats.iter().filter(|(_, s)| s.count >= MIN_COUNT && s.intervals >= 2
			&& s.speed >= MIN_SPEED && (MIN_INTERVAL..=MAX_INTERVAL).contains(&s.interval)).collect();
		let top = ready.iter().map(|(_, s)| s.count).max()?;
		// Rolls and attacks also move the body, but footsteps dominate the counts.
		ready.into_iter().filter(|(_, s)| s.count * 4 >= top)
			.min_by(|a, b| (a.1.speed - speed).abs().total_cmp(&(b.1.speed - speed).abs()))
			.map(|(id, s)| (*id, *s))
	}

	/// `body` is ER's player position in Minecraft coordinates. `driving` means Minecraft moves the
	/// live, acknowledged body this frame; otherwise ER's own animations are moving it.
	pub fn frame(&mut self, player: &PlayerIns, havok: [f32; 3], body: [f64; 3], driving: bool, mc: Option<&McView>) {
		let handle: u64 = unsafe { std::mem::transmute_copy(&player.chr_ins.field_ins_handle) };
		WATCH.store(handle, Ordering::Relaxed);
		let now = Instant::now();
		match self.last_body.replace((body, now)) {
			Some((old, at)) => {
				let dt = now.duration_since(at).as_secs_f64();
				let step = (body[0] - old[0]).hypot(body[2] - old[2]);
				if dt > 0.0 && dt <= 0.25 && step / dt < 20.0 {
					self.speed += (step / dt - self.speed) * (dt / 0.15).min(1.0);
				} else {
					self.speed = 0.0; // a load, warp or stall is not movement
				}
			}
			None => self.speed = 0.0,
		}
		let heard = std::mem::take(&mut *HEARD.lock().unwrap_or_else(|e| e.into_inner()));
		if !driving {
			for h in heard {
				if h.ours {
					self.learn(h.id, now);
				} else {
					let d = ((h.pos[0] - havok[0]).powi(2) + (h.pos[1] - havok[1]).powi(2) + (h.pos[2] - havok[2]).powi(2)).sqrt();
					if d < 1.5 && self.nearby_other.len() < 32 { *self.nearby_other.entry((h.kind, h.id)).or_default() += 1; }
				}
			}
		} else if let Some(m) = mc {
			let quiet = m.flags & (proto::MC_SNEAKING | proto::MC_SWIMMING | proto::MC_FLYING | proto::MC_DEAD) != 0
				|| m.flags & proto::MC_ON_GROUND == 0;
			if !quiet && self.speed >= MIN_SPEED {
				if let Some((id, s)) = self.footstep(self.speed) {
					if self.chosen != Some(id) {
						self.chosen = Some(id);
						log::line(&format!("ai sound: MC footsteps use learned ER sound {id} (learned at {:.1} m/s every {:.2} s; now {:.1} m/s)",
							s.speed, s.interval, self.speed));
					}
					// Faster movement steps more often, like the native cadence.
					let every = s.interval * (s.speed / self.speed).clamp(0.6, 1.6);
					if self.last_step.is_none_or(|t| now.duration_since(t).as_secs_f64() >= every) {
						self.last_step = Some(now);
						if emit(player, id) { self.emitted += 1; }
					}
				}
			}
		}
		self.report(driving, now);
	}

	fn learn(&mut self, id: i32, now: Instant) {
		if !self.stats.contains_key(&id) && self.stats.len() >= 256 { return; }
		let speed = self.speed;
		let s = self.stats.entry(id).or_default();
		if s.count == 0 {
			log::line(&format!("ai sound: player emitted AiSoundParam {id} at {speed:.1} m/s (first time)"));
		}
		s.count = s.count.saturating_add(1);
		let n = f64::from(s.count).min(MEAN_WINDOW);
		s.speed += (speed - s.speed) / n;
		if let Some(prev) = self.last_heard.insert(id, now) {
			let gap = now.duration_since(prev).as_secs_f64();
			if speed >= MIN_SPEED && gap <= MAX_INTERVAL * 1.5 {
				s.intervals = s.intervals.saturating_add(1);
				s.interval += (gap - s.interval) / f64::from(s.intervals).min(MEAN_WINDOW);
			}
		}
		self.dirty = true;
	}

	fn report(&mut self, driving: bool, now: Instant) {
		if self.dirty && now >= self.next_save {
			self.next_save = now + Duration::from_secs(30);
			self.dirty = false;
			let mut rows: Vec<_> = self.stats.iter().collect();
			rows.sort_by(|a, b| b.1.count.cmp(&a.1.count));
			let mut text = String::from("# AiSoundParam ID, count, mean body speed m/s, mean repeat s, repeats (learned in ER mode)\n");
			for (id, s) in rows.into_iter().take(64) {
				text.push_str(&format!("{id} {} {:.3} {:.3} {}\n", s.count, s.speed, s.interval, s.intervals));
			}
			if let Err(e) = std::fs::write(&self.path, text) {
				log::line(&format!("ai sound: couldn't save {}: {e}", self.path.display()));
			}
		}
		if now < self.next_report { return; }
		self.next_report = now + Duration::from_secs(10);
		if driving {
			match self.footstep(self.speed.max(MIN_SPEED)) {
				Some(_) => log::line(&format!("ai sound: {} MC footsteps emitted in 10 s (speed {:.1} m/s)", self.emitted, self.speed)),
				None => log::line("ai sound: no footstep learned yet; walk and run in ER mode (F8 off) so MC mode can make noise"),
			}
			self.emitted = 0;
		} else if !self.stats.is_empty() {
			let mut rows: Vec<_> = self.stats.iter().collect();
			rows.sort_by(|a, b| b.1.count.cmp(&a.1.count));
			let top: Vec<_> = rows.iter().take(6).map(|(id, s)| format!("{id} x{} @{:.1}m/s /{:.2}s", s.count, s.speed, s.interval)).collect();
			let others: Vec<_> = self.nearby_other.iter().take(6).map(|((k, id), n)| format!("kind {k} id {id} x{n}")).collect();
			log::line(&format!("ai sound: learned player sounds [{}]; footstep at run speed {:?}; other sounds at the body [{}]",
				top.join(", "), self.footstep(4.5).map(|(id, _)| id), others.join(", ")));
			self.nearby_other.clear();
		}
	}
}

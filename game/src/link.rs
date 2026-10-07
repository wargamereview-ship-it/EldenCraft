//! The shared memory Minecraft opens. The game creates it; Minecraft finds it by name.

use std::ptr::{addr_of, addr_of_mut, null};
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering, fence};

use windows_sys::Win32::Foundation::{GetLastError, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Memory::{CreateFileMappingW, FILE_MAP_ALL_ACCESS, MapViewOfFile, PAGE_READWRITE};
use windows_sys::Win32::System::SystemInformation::GetTickCount64;
use windows_sys::Win32::System::Threading::GetCurrentProcessId;

use crate::log;
use crate::proto::{self, GameState, Header, McStateHead};

pub struct Link {
	base: *mut u8,
}

// The mapping lives for the whole process and every access is through atomics or a seqlock.
unsafe impl Send for Link {}
unsafe impl Sync for Link {}

/// What the game publishes each frame (everything in GameState except the seqlock).
pub struct Publish {
	pub flags: u32,
	pub world_id: u32,
	pub collision_epoch: u32,
	pub pos: [f64; 3],
	pub yaw: f32,
	pub pitch: f32,
	pub teleport_seq: u32,
	pub viewport: (u32, u32),
}

#[derive(Clone)]
pub struct McView {
	/// Version of the coherent snapshot, used to detect a stopped Minecraft publisher.
	pub sequence: u32,
	pub in_world: bool,
	pub pos: [f64; 3],
	/// Latest physics tick, independent of the interpolated position used for drawing.
	pub feet: [f64; 3],
	pub tick_qpc: i64,
	pub eye_height: f32,
	pub sensitivity: f32,
	pub teleport_ack: u32,
	pub fov_deg: f32,
	pub eye: [f64; 3],
	pub screen_open: bool,
	pub gui_scale: u32,
	pub camera_mode: u32,
	pub camera_distance: f32,
	/// Raw `MC_*` state flags (on ground, sneaking, swimming, ...).
	pub flags: u32,
}

/// Which Elden Ring character is playing: save slot (low 4 bits, slot + 1) and a 20-bit hash of its name above them.
/// Published in the high 24 bits of the life flags so Minecraft can keep a separate world for each character.
static PROFILE: AtomicU32 = AtomicU32::new(0);

pub fn set_profile(profile: u32) {
	PROFILE.store(profile & 0x00FF_FFFF, Ordering::Relaxed);
}

impl Link {
	pub fn publish_life(&self, epoch: u32, world_id: u32, flags: u32) {
		if flags & proto::LIFE_ACTIVE == 0 { self.publish_statuses(epoch, world_id, None); }
		unsafe {
			let p = self.base.add(proto::OFF_NATIVE_LIFE).cast::<proto::NativeLife>();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*p).seq));
			let start = seq.load(Ordering::Relaxed) | 1;
			seq.store(start, Ordering::Relaxed); fence(Ordering::Release);
			(*p).epoch = epoch; (*p).world_id = world_id; (*p).flags = (flags & 0xFF) | PROFILE.load(Ordering::Relaxed) << 8;
			(*p).updated_ms = GetTickCount64();
			fence(Ordering::Release); seq.store(start.wrapping_add(1), Ordering::Release);
		}
	}

	pub fn publish_statuses(&self, epoch: u32, world_id: u32, status: Option<crate::player_status::Statuses>) {
		unsafe {
			let p = self.base.add(proto::OFF_PLAYER_STATUSES).cast::<proto::PlayerStatuses>();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*p).seq));
			let start = seq.load(Ordering::Relaxed) | 1;
			seq.store(start, Ordering::Relaxed); fence(Ordering::Release);
			let data = status.unwrap_or_default();
			(*p).epoch = epoch; (*p).world_id = world_id;
			(*p).flags = if status.is_some() { proto::STATUSES_VALID } else { 0 };
			(*p).updated_ms = GetTickCount64();
			(*p).buildup = data.buildup; (*p).maximum = data.maximum;
			(*p).remaining = data.remaining; (*p).duration = data.duration;
			fence(Ordering::Release); seq.store(start.wrapping_add(1), Ordering::Release);
		}
	}

	pub fn read_vitals(&self, epoch: u32, world_id: u32) -> Option<proto::PlayerVitals> {
		unsafe {
			let p = self.base.add(proto::OFF_PLAYER_VITALS).cast::<proto::PlayerVitals>();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*p).seq));
			for _ in 0..4 {
				let first = seq.load(Ordering::Acquire);
				if first & 1 != 0 { continue; }
				let v = p.read_volatile(); fence(Ordering::Acquire);
				if first != seq.load(Ordering::Relaxed) { continue; }
				if v.flags & proto::VITALS_VALID == 0 || v.epoch != epoch || v.world_id != world_id
					|| GetTickCount64().saturating_sub(v.updated_ms) > 1000
					|| !v.health.is_finite() || !v.max_health.is_finite() || !v.absorption.is_finite()
					|| v.health < 0.0 || v.max_health <= 0.0 || v.health > v.max_health || v.absorption < 0.0 { return None; }
				return Some(v);
			}
			None
		}
	}

	pub fn publish_ground(&self, world_id: u32, epoch: u32, patch: Option<([f64; 2], f64, [f64; 5])>) {
		unsafe {
			let p = self.base.add(proto::OFF_PLAYER_GROUND).cast::<proto::PlayerGround>();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*p).seq));
			let start = seq.load(Ordering::Relaxed) | 1;
			seq.store(start, Ordering::Relaxed);
			fence(Ordering::Release);
			let ([x, z], radius, heights) = patch.unwrap_or(([0.0; 2], 0.0, [0.0; 5]));
			(*p).world_id = world_id;
			(*p).epoch = epoch;
			(*p).valid = u32::from(patch.is_some());
			(*p).updated_ms = GetTickCount64();
			(*p).x = x;
			(*p).z = z;
			(*p).radius = radius;
			(*p).heights = heights;
			fence(Ordering::Release);
			seq.store(start.wrapping_add(1), Ordering::Release);
		}
	}

	pub fn create() -> Result<Self, String> {
		unsafe {
			let size = proto::MAPPING_BYTES;
			let handle = CreateFileMappingW(
				INVALID_HANDLE_VALUE,
				null(),
				PAGE_READWRITE,
				(size >> 32) as u32,
				size as u32,
				proto::MAPPING_NAME.as_ptr(),
			);
			if handle.is_null() {
				return Err(format!("CreateFileMappingW failed ({})", GetLastError()));
			}
			let view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, 0);
			if view.Value.is_null() {
				return Err(format!("MapViewOfFile failed ({})", GetLastError()));
			}
			let link = Self { base: view.Value.cast() };
			let header = link.header();
			(*header).game_pid = GetCurrentProcessId();
			(*header).version = proto::VERSION;
			fence(Ordering::Release);
			// Magic last: Minecraft treats the mapping as ready once it sees it.
			AtomicU32::from_ptr(addr_of_mut!((*header).magic)).store(proto::MAGIC, Ordering::Release);
			Ok(link)
		}
	}

	fn header(&self) -> *mut Header {
		unsafe { self.base.add(proto::OFF_HEADER).cast() }
	}

	pub fn heartbeat(&self) {
		unsafe {
			let header = self.header();
			AtomicU64::from_ptr(addr_of_mut!((*header).game_heartbeat_ms)).store(GetTickCount64(), Ordering::Release);
		}
	}

	pub fn publish(&self, p: &Publish) {
		unsafe {
			let s: *mut GameState = self.base.add(proto::OFF_GAME_STATE).cast();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*s).seq));
			let start = seq.load(Ordering::Relaxed) | 1;
			seq.store(start, Ordering::Relaxed);
			fence(Ordering::Release);
			(*s).flags = p.flags;
			(*s).world_id = p.world_id;
			(*s).collision_epoch = p.collision_epoch;
			(*s).pos_x = p.pos[0];
			(*s).pos_y = p.pos[1];
			(*s).pos_z = p.pos[2];
			(*s).yaw = p.yaw;
			(*s).pitch = p.pitch;
			(*s).teleport_seq = p.teleport_seq;
			(*s).viewport_w = p.viewport.0;
			(*s).viewport_h = p.viewport.1;
			fence(Ordering::Release);
			seq.store(start.wrapping_add(1), Ordering::Release);
		}
	}

	pub fn mc_connected(&self) -> bool {
		unsafe {
			let header = self.header();
			AtomicU32::from_ptr(addr_of_mut!((*header).mc_pid)).load(Ordering::Acquire) != 0
		}
	}

	/// Minecraft's player, or None while Minecraft is mid-write.
	pub fn read_mc(&self) -> Option<McView> {
		unsafe {
			let s: *mut McStateHead = self.base.add(proto::OFF_MC_STATE).cast();
			let seq = AtomicU32::from_ptr(addr_of_mut!((*s).seq));
			let seq1 = seq.load(Ordering::Acquire);
			if seq1 & 1 != 0 {
				return None;
			}
			let flags = addr_of!((*s).flags).read_volatile();
			let view = McView {
				sequence: seq1,
				in_world: flags & proto::MC_IN_WORLD != 0,
				pos: [addr_of!((*s).x).read_volatile(), addr_of!((*s).y).read_volatile(), addr_of!((*s).z).read_volatile()],
				feet: addr_of!((*s).cur).read_volatile(),
				tick_qpc: addr_of!((*s).tick_qpc).read_volatile(),
				eye_height: addr_of!((*s).eye_height).read_volatile(),
				sensitivity: addr_of!((*s).sensitivity).read_volatile(),
				teleport_ack: addr_of!((*s).teleport_ack).read_volatile(),
				fov_deg: addr_of!((*s).fov_deg).read_volatile(),
				eye: addr_of!((*s).eye).read_volatile(),
				screen_open: flags & proto::MC_SCREEN_OPEN != 0,
				gui_scale: addr_of!((*s).gui_scale).read_volatile(),
				camera_mode: addr_of!((*s).camera_mode).read_volatile(),
				camera_distance: addr_of!((*s).camera_distance).read_volatile(),
				flags,
			};
			fence(Ordering::Acquire);
			(seq.load(Ordering::Relaxed) == seq1).then_some(view)
		}
	}
}

/// Plain-old-data as bytes, for writing protocol structs into the rings.
pub fn bytes_of<T: Copy>(v: &T) -> &[u8] {
	unsafe { std::slice::from_raw_parts((v as *const T).cast(), size_of::<T>()) }
}

pub fn bytes_of_slice<T: Copy>(v: &[T]) -> &[u8] {
	unsafe { std::slice::from_raw_parts(v.as_ptr().cast(), size_of_val(v)) }
}

impl Link {
	fn counter(&self, off: usize) -> &AtomicU64 {
		unsafe { AtomicU64::from_ptr(self.base.add(off).cast()) }
	}

	/// Appends one collision message. False if Minecraft hasn't made room yet (try again later).
	pub fn write_collision(&self, kind: u32, parts: &[&[u8]]) -> bool {
		let ring = proto::OFF_COLLISION_RING;
		let size = proto::COLLISION_DATA_BYTES;
		let payload: usize = parts.iter().map(|p| p.len()).sum();
		let msg = (8 + payload + 7) & !7;
		if msg > size / 2 {
			log::line(&format!("collision message too large ({msg} bytes); dropped"));
			return true;
		}
		let head_c = self.counter(ring + proto::RING_HEAD);
		let mut head = head_c.load(Ordering::Relaxed) as usize;
		let tail = self.counter(ring + proto::RING_TAIL).load(Ordering::Acquire) as usize;
		let mut pos = head % size;
		let pad = if pos + msg > size { size - pos } else { 0 };
		if size - (head - tail) < msg + pad {
			return false;
		}
		unsafe {
			let data = self.base.add(ring + proto::RING_DATA);
			if pad > 0 {
				data.add(pos).cast::<[u32; 2]>().write_unaligned([proto::COL_PAD, 0]);
				head += pad;
				pos = 0;
			}
			let at = data.add(pos);
			at.cast::<[u32; 2]>().write_unaligned([kind, payload as u32]);
			let mut off = 8;
			for p in parts {
				std::ptr::copy_nonoverlapping(p.as_ptr(), at.add(off), p.len());
				off += p.len();
			}
		}
		head_c.store((head + msg) as u64, Ordering::Release);
		true
	}

	/// Hands every pending render message to `f` (type, payload), up to about `budget` bytes.
	pub fn read_render(&self, budget: usize, mut f: impl FnMut(u32, &[u8])) {
		let ring = proto::OFF_RENDER_RING;
		let size = proto::RENDER_DATA_BYTES;
		let head = self.counter(ring + proto::RING_HEAD).load(Ordering::Acquire) as usize;
		let tail_c = self.counter(ring + proto::RING_TAIL);
		let mut tail = tail_c.load(Ordering::Relaxed) as usize;
		let start = tail;
		unsafe {
			let data = self.base.add(ring + proto::RING_DATA);
			while tail < head && tail - start < budget {
				let pos = tail % size;
				let [kind, payload] = data.add(pos).cast::<[u32; 2]>().read_unaligned();
				if kind == proto::REN_PAD {
					tail += size - pos;
					continue;
				}
				f(kind, std::slice::from_raw_parts(data.add(pos + 8), payload as usize));
				tail += (8 + payload as usize + 7) & !7;
			}
		}
		tail_c.store(tail as u64, Ordering::Release);
	}

	pub fn send_input(&self, kind: u16, code: u16, a: i32) {
		self.send_input_full(kind, code, a, 0, 0);
	}

	pub fn send_input_xy(&self, kind: u16, x: i32, y: i32) {
		self.send_input_full(kind, 0, x, y, 0);
	}

	pub fn send_input_full(&self, kind: u16, code: u16, a: i32, b: i32, c: i32) {
		self.send_inputs(&[proto::InputEvent { kind, code, a, b, c }]);
	}

 /// One release publishes the whole batch, so a hit can never lose its source position.
	pub fn send_inputs(&self, events: &[proto::InputEvent]) -> bool {
		let ring = proto::OFF_INPUT_RING;
		let head_c = self.counter(ring + proto::RING_HEAD);
		let head = head_c.load(Ordering::Relaxed);
		let tail = self.counter(ring + proto::RING_TAIL).load(Ordering::Acquire);
		if head.wrapping_sub(tail).saturating_add(events.len() as u64) > proto::INPUT_RING_ENTRIES {
			return false;
		}
		for (i, event) in events.iter().enumerate() {
			let slot = (head.wrapping_add(i as u64) & (proto::INPUT_RING_ENTRIES - 1)) as usize;
			unsafe {
				let e = self.base.add(ring + proto::RING_DATA).cast::<proto::InputEvent>().add(slot);
				e.write_volatile(proto::InputEvent { kind: event.kind, code: event.code, a: event.a, b: event.b, c: event.c });
			}
		}
		head_c.store(head.wrapping_add(events.len() as u64), Ordering::Release);
		true
	}
}

impl Link {
	/// Single native frame-thread writer; readers retry if this table changes mid-read.
	pub fn publish_actors(&self, actors: &[proto::ActorRecord]) {
		unsafe {
			let base = self.base.add(proto::OFF_ACTOR_TABLE);
			let seq = AtomicU32::from_ptr(base.cast());
			let start = seq.load(Ordering::Relaxed) | 1;
			seq.store(start, Ordering::Relaxed);
			fence(Ordering::Release);
			let count = actors.len().min(proto::MAX_ACTORS);
			base.add(4).cast::<u32>().write_volatile(count as u32);
			let records = base.add(0x40).cast::<proto::ActorRecord>();
			for (i, actor) in actors[..count].iter().enumerate() {
				records.add(i).write_volatile(*actor);
			}
			fence(Ordering::Release);
			seq.store(start.wrapping_add(1), Ordering::Release);
		}
	}

	/// Drain one bounded snapshot. Unknown event types must also be consumed.
	pub fn read_events(&self) -> Vec<proto::McEvent> {
		let ring = proto::OFF_EVENT_RING;
		let head = self.counter(ring + proto::RING_HEAD).load(Ordering::Acquire);
		let tail_c = self.counter(ring + proto::RING_TAIL);
		let mut tail = tail_c.load(Ordering::Relaxed);
		if head.wrapping_sub(tail) > proto::EVENT_RING_ENTRIES {
			// A restarted writer/corrupt counter is not permission to replay arbitrary memory.
			tail_c.store(head, Ordering::Release);
			return Vec::new();
		}
		let mut events = Vec::with_capacity(head.wrapping_sub(tail) as usize);
		while tail != head {
			let slot = (tail & (proto::EVENT_RING_ENTRIES - 1)) as usize;
			unsafe {
				events.push(self.base.add(ring + proto::RING_DATA).cast::<proto::McEvent>().add(slot).read_volatile());
			}
			tail = tail.wrapping_add(1);
		}
		tail_c.store(tail, Ordering::Release);
		events
	}
}

impl Link {
	/// Another handle on the same mapping (for the render thread).
	pub fn share(&self) -> Link {
		Link { base: self.base }
	}

	/// Takes Minecraft's newest GUI frame, if there is one since the last: the new front slot,
	/// its size and whether its rows are bottom-up. `front` is the slot held until now.
	pub fn acquire_overlay(&self, front: usize) -> Option<(usize, u32, u32, bool)> {
		unsafe {
			let state = AtomicU32::from_ptr(self.base.add(proto::OFF_OVERLAY_CTL).cast());
			if state.load(Ordering::Acquire) & proto::OVERLAY_DIRTY == 0 {
				return None;
			}
			let front = (state.swap(front as u32, Ordering::AcqRel) & 3) as usize;
			if front > 2 {
				return None;
			}
			let hdr = self.base.add(proto::OFF_OVERLAY_SLOT_HDR + 0x40 * front).cast::<[u32; 3]>().read_volatile();
			let [w, h, flags] = hdr;
			(w > 0 && h > 0 && w <= proto::MAX_OVERLAY_SIDE && h <= proto::MAX_OVERLAY_SIDE && (w as usize * h as usize * 4) <= proto::OVERLAY_SLOT_BYTES).then_some((front, w, h, flags & 1 != 0))
		}
	}

	pub fn overlay_pixels(&self, slot: usize, width: u32, height: u32) -> &[u8] {
		unsafe {
			std::slice::from_raw_parts(self.base.add(proto::OFF_OVERLAY_PIXELS + proto::OVERLAY_SLOT_BYTES * slot), (width * height * 4) as usize)
		}
	}
}

impl Link {
	/// Minecraft's dropped items, arrows, block cracks and shadows this frame, and the outline of
	/// the block it targets (None while it is mid-write).
	pub fn read_world_entities(&self) -> Option<(Vec<proto::WorldEntity>, Option<[f32; 6]>)> {
		unsafe {
			let base = self.base.add(proto::OFF_WORLD_ENTITIES);
			let seq = AtomicU32::from_ptr(base.cast());
			let seq1 = seq.load(Ordering::Acquire);
			if seq1 & 1 != 0 {
				return None;
			}
			let count = (base.add(4).cast::<u32>().read_volatile() as usize).min(proto::MAX_WORLD_ENTITIES);
			let records = base.add(0x40).cast::<proto::WorldEntity>();
			let out = (0..count).map(|i| records.add(i).read_volatile()).collect();
			let selection = (base.add(8).cast::<u32>().read_volatile() != 0).then(|| base.add(12).cast::<[f32; 6]>().read_volatile());
			fence(Ordering::Acquire);
			(seq.load(Ordering::Relaxed) == seq1).then_some((out, selection))
		}
	}
}

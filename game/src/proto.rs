//! Rust mirror of `protocol/eldencraft_protocol.h` (the subset the game DLL uses so far).
//! The C header is the source of truth for the byte layout; Java mirrors it in `Proto.java`.
//! Change all three together; bump `VERSION` for incompatible changes.

pub const MAGIC: u32 = 0x4344_4C45; // "ELDC"
pub const VERSION: u32 = 3;
/// NUL-terminated UTF-16 for Win32, from an ASCII literal.
const fn wide<const N: usize>(s: &str) -> [u16; N] {
	let bytes = s.as_bytes();
	assert!(bytes.len() + 1 == N);
	let mut out = [0u16; N];
	let mut i = 0;
	while i < bytes.len() {
		out[i] = bytes[i] as u16;
		i += 1;
	}
	out
}

const MAPPING_NAME_STR: &str = "Local\\EldenCraft_v1";
pub const MAPPING_NAME: [u16; MAPPING_NAME_STR.len() + 1] = wide(MAPPING_NAME_STR);

pub const OFF_HEADER: usize = 0x0;
pub const OFF_GAME_STATE: usize = 0x100; // "SkyState" in the header and Java
pub const OFF_MC_STATE: usize = 0x200;
pub const OFF_INPUT_RING: usize = 0x1000;
pub const OFF_ACTOR_TABLE: usize = 0x12000;
pub const OFF_EVENT_RING: usize = 0x17000;
/// Optional live ground patch; after WaterGrid (ends 0x810), before the input ring.
pub const OFF_PLAYER_GROUND: usize = 0x840;

#[repr(C)]
pub struct PlayerGround {
	pub seq: u32,
	pub world_id: u32,
	pub epoch: u32,
	pub valid: u32,
	pub updated_ms: u64,
	pub x: f64,
	pub z: f64,
	pub radius: f64,
	/// Center, then corners (-x,-z), (+x,-z), (+x,+z), (-x,+z).
	pub heights: [f64; 5],
}
const _: () = assert!(size_of::<PlayerGround>() == 88);
const _: () = assert!(OFF_PLAYER_GROUND + size_of::<PlayerGround>() < OFF_INPUT_RING);
pub const MAX_ACTORS: usize = 256;
pub const EVENT_RING_ENTRIES: u64 = 512;
pub const OFF_COLLISION_RING: usize = 0x20000;
const COLLISION_RING_BYTES: usize = 32 << 20;
pub const OFF_OVERLAY_CTL: usize = 0x300;
pub const OFF_OVERLAY_SLOT_HDR: usize = 0x340; // 3 x 0x40: width, height, flags (bit0 bottom-up)
pub const OFF_OVERLAY_PIXELS: usize = OFF_COLLISION_RING + COLLISION_RING_BYTES;
pub const OVERLAY_DIRTY: u32 = 1 << 2;
/// An overlay may be any size whose pixels fit a slot (e.g. 5120x1440), each side up to this.
pub const MAX_OVERLAY_SIDE: u32 = 8192;
pub const OVERLAY_SLOT_BYTES: usize = 3840 * 2160 * 4;
pub const OFF_RENDER_RING: usize = OFF_COLLISION_RING + COLLISION_RING_BYTES + OVERLAY_SLOT_BYTES * 3;
const RENDER_RING_BYTES: usize = 64 << 20;
pub const MAPPING_BYTES: u64 = (OFF_RENDER_RING + RENDER_RING_BYTES) as u64;

// Byte rings (collision, render): u64 head (bytes written) @0, u64 tail (bytes consumed) @0x40,
// data @0x80. Messages are 8-byte aligned {u32 type, u32 payload bytes}; type 0 pads to the start.
pub const RING_HEAD: usize = 0x00;
pub const RING_TAIL: usize = 0x40;
pub const RING_DATA: usize = 0x80;
pub const COLLISION_DATA_BYTES: usize = COLLISION_RING_BYTES - RING_DATA;
pub const RENDER_DATA_BYTES: usize = RENDER_RING_BYTES - RING_DATA;

// Input ring: u64 head (events written) @0, u64 tail @0x40, InputEvent[4096] @0x80.
pub const INPUT_RING_ENTRIES: u64 = 4096;

pub const IN_KEY: u16 = 1;
pub const IN_MOUSE_BUTTON: u16 = 2;
pub const IN_SCROLL: u16 = 3;
pub const IN_CURSOR: u16 = 4;
pub const IN_TEXT: u16 = 5;
pub const IN_RELEASE_ALL: u16 = 6;
/// code = HitFlags, a = actual ER HP removed, b = transient actor ID, c = remaining ER HP.
pub const IN_HIT_FEEDBACK: u16 = 9;
/// a = native player HP, b = maximum native player HP. Both zero clears the display.
pub const IN_PLAYER_HEALTH: u16 = 10;
/// Atomically precedes IN_HURT: source position as f32 bits; code 1 when known.
pub const IN_HURT_ORIGIN: u16 = 11;
pub const IN_HURT: u16 = 7;
/// IN_HURT codes: how the hit reached the player (Proto.HURT_* / HurtKind).
pub const HURT_MELEE: u16 = 0;
pub const HURT_PROJECTILE: u16 = 1;
pub const HURT_OTHER: u16 = 3;
/// An enemy the player last hit died, sent as one batch: corpse position (a/b/c f32 bits),
/// identity (a = map event entity ID, b = world ID, c = NpcParam ID), then the death itself
/// (code = ENEMY_BOSS, a = native max HP, b = character model ID, c = actor ID).
pub const IN_LOOT_POS: u16 = 12;
pub const IN_LOOT_ID: u16 = 13;
pub const IN_ENEMY_DIED: u16 = 14;
/// a = raw native map block, b = play region. Precedes each death; c of death is boss completion flag.
pub const IN_LOOT_REGION: u16 = 15;
/// A native grace-rest edge, distinct from map loads and respawn.
pub const IN_GRACE_REST: u16 = 16;
/// Atomically precedes IN_HURT, code 1 when the hit's attack is known: a = magic, fire, lightning,
/// holy percent (a byte each, low first), b = physical percent.
pub const IN_HURT_ELEMENTS: u16 = 17;
pub const ENEMY_BOSS: u16 = 1;
pub const OFF_NATIVE_LIFE: usize = 0x900;
pub const OFF_PLAYER_VITALS: usize = 0x940;
pub const LIFE_ACTIVE: u32 = 1;
pub const LIFE_RESPAWN: u32 = 2;
pub const VITALS_VALID: u32 = 1;
pub const VITALS_DEAD: u32 = 2;

#[repr(C)]
pub struct NativeLife {
	pub seq: u32, pub epoch: u32, pub world_id: u32, pub flags: u32,
	pub updated_ms: u64,
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct PlayerVitals {
	pub seq: u32, pub epoch: u32, pub world_id: u32, pub flags: u32,
	pub updated_ms: u64,
	pub health: f32, pub max_health: f32, pub absorption: f32,
	/// Ward levels 0-7, a byte each, low first: poison, scarlet rot, blood loss, frostbite.
	pub status_wards: u32,
}
const _: () = assert!(size_of::<NativeLife>() == 24);
const _: () = assert!(size_of::<PlayerVitals>() == 40);
const _: () = assert!(OFF_PLAYER_GROUND + size_of::<PlayerGround>() <= OFF_NATIVE_LIFE);
const _: () = assert!(OFF_NATIVE_LIFE + size_of::<NativeLife>() <= OFF_PLAYER_VITALS);
const _: () = assert!(OFF_PLAYER_VITALS + size_of::<PlayerVitals>() <= OFF_INPUT_RING);
/// Optional version-3 extension; older DLLs leave flags zero.
pub const OFF_PLAYER_STATUSES: usize = 0x980;
pub const STATUS_COUNT: usize = 7;
pub const STATUSES_VALID: u32 = 1;
#[repr(C)]
pub struct PlayerStatuses {
	pub seq: u32, pub epoch: u32, pub world_id: u32, pub flags: u32,
	pub updated_ms: u64,
	pub buildup: [u32; STATUS_COUNT], pub maximum: [u32; STATUS_COUNT],
	/// Seconds remaining; -1 is active without a timer, zero is inactive.
	pub remaining: [f32; STATUS_COUNT], pub duration: [f32; STATUS_COUNT],
}
const _: () = assert!(size_of::<PlayerStatuses>() == 136);
const _: () = assert!(OFF_PLAYER_VITALS + size_of::<PlayerVitals>() <= OFF_PLAYER_STATUSES);
const _: () = assert!(OFF_PLAYER_STATUSES + size_of::<PlayerStatuses>() <= OFF_INPUT_RING);
pub const HIT_REJECTED: u16 = 1 << 15;
pub const EV_HIT_ACTOR: u32 = 1;
pub const HIT_CRITICAL: u32 = 1 << 0;
/// McEvent.weapon: which kind of MC weapon dealt a hit (Proto.WEAPON_* / kWeapon*).
pub const WEAPON_BLADE: u32 = 1;
pub const WEAPON_AXE: u32 = 2;
pub const WEAPON_BLUNT: u32 = 3;
pub const WEAPON_PIERCE: u32 = 4;
pub const WEAPON_ARROW: u32 = 5;
pub const WEAPON_SPEAR: u32 = 6;
pub const WEAPON_MACE: u32 = 7;
pub const WEAPON_MACE_SMASH: u32 = 8;
pub const HIT_PROJECTILE: u32 = 1 << 1;
pub const HIT_FIRE: u32 = 1 << 3;
/// Status damage (poison, rot, a hemorrhage or frostbite proc): straight to HP, no bullet, no stagger.
pub const HIT_STATUS: u32 = 1 << 4;
pub const ACTOR_HOSTILE: u32 = 1;
pub const ACTOR_DEAD: u32 = 1 << 1;
pub const ACTOR_ESSENTIAL: u32 = 1 << 2;

/// The legacy formId field carries a session-local ER actor ID, never a native pointer.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ActorRecord {
	pub id: u32,
	pub flags: u32,
	pub pos: [f32; 3],
	pub yaw: f32,
	pub width: f32,
	pub height: f32,
	pub health_frac: f32,
	pub level: u16,
	pub pad: u16,
	pub name: [u8; 24],
}
const _: () = assert!(size_of::<ActorRecord>() == 64);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct McEvent {
	pub kind: u32,
	pub id: u32,
	pub a: f32,
	pub b: f32,
	pub c: f32,
	pub d: f32,
	pub flags: u32,
	pub weapon: u32,
}
const _: () = assert!(size_of::<McEvent>() == 32);

pub const COL_PAD: u32 = 0;
pub const COL_CLEAR: u32 = 1;
pub const COL_REGION: u32 = 2;
pub const COL_TRIS: u32 = 3;

pub const REN_PAD: u32 = 0;
pub const REN_ATLAS: u32 = 1;
pub const REN_SECTION: u32 = 2;
pub const REN_CLEAR_ALL: u32 = 3;
pub const REN_TEXTURE: u32 = 4;
pub const REN_AVATAR: u32 = 5;
pub const REN_SCENE: u32 = 6;

pub const OFF_WORLD_ENTITIES: usize = 0x1C000;
pub const MAX_WORLD_ENTITIES: usize = 160;
pub const WE_ARROW: u32 = 1;
pub const WE_ITEM: u32 = 2;
pub const WE_TRIDENT: u32 = 3;
pub const WE_BLOCK: u32 = 4;
pub const WE_CRACK: u32 = 5;
pub const WE_SHADOW: u32 = 6;

/// Minecraft -> game, rewritten every frame (seq odd while writing).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct WorldEntity {
	pub kind: u32,
	pub id: u32,
	pub pos: [f32; 3], // Minecraft coords
	pub yaw: f32,
	pub pitch: f32,
	pub scale: f32,
	pub ext: [f32; 3],
	pub uv: [[f32; 4]; 3], // atlas rects {u0, v0, u1, v1}
	pub tint: u32,         // RGBA8 for a block's top face; 0 none
}
const _: () = assert!(size_of::<WorldEntity>() == 96);

/// Header of kColRegion and kColTris.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ColRegion {
	pub min: [i32; 3],
	pub max: [i32; 3],
	pub epoch: u32,
	pub count: u32,
}
const _: () = assert!(size_of::<ColRegion>() == 32);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ColTri {
	pub v: [f32; 9],
	pub flags: u32,
}
const _: () = assert!(size_of::<ColTri>() == 40);

/// One block of collision as an 8x8x8 mask: bits[y] bit (z * 8 + x).
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ColBlock {
	pub pos: [i32; 3],
	pub pad: u32,
	pub bits: [u64; 8],
}
const _: () = assert!(size_of::<ColBlock>() == 80);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct RenVertex {
	pub pos: [f32; 3], // relative to the section origin
	pub u: f32,
	pub v: f32,
	pub color: u32, // RGBA8, r low byte
	pub light: u32,
	pub flags: u32, // bit0 cutout, bit1 translucent, bits 4-6 face direction + 1
}
const _: () = assert!(size_of::<RenVertex>() == 32);

#[repr(C)]
pub struct InputEvent {
	pub kind: u16,
	pub code: u16,
	pub a: i32,
	pub b: i32,
	pub c: i32,
}
const _: () = assert!(size_of::<InputEvent>() == 16);

#[repr(C)]
pub struct Header {
	pub magic: u32,
	pub version: u32,
	pub game_pid: u32,
	pub mc_pid: u32,
	pub game_heartbeat_ms: u64, // GetTickCount64() at the last game frame
	pub mc_heartbeat_ms: u64,
}
const _: () = assert!(size_of::<Header>() == 0x20);

pub const GAME_IN_GAME: u32 = 1 << 0;
pub const GAME_MENU_OPEN: u32 = 1 << 1;
pub const GAME_LOADING: u32 = 1 << 2;
/// The native camera is actually showing Minecraft first person (HUD visibility is independent).
pub const GAME_MC_HANDS: u32 = 1 << 3;

/// Game -> MC, seqlock (`seq` odd while writing).
#[repr(C)]
pub struct GameState {
	pub seq: u32,
	pub flags: u32,
	pub world_id: u32, // Elden Ring map block id of the player
	pub collision_epoch: u32,
	pub pos_x: f64, // player feet, MC coords
	pub pos_y: f64,
	pub pos_z: f64,
	pub yaw: f32, // MC degrees
	pub pitch: f32,
	pub teleport_seq: u32, // MC teleports its player to pos when this changes
	pub viewport_w: u32,
	pub viewport_h: u32,
	pub game_hour: f32,
}
const _: () = assert!(size_of::<GameState>() == 0x40);

/// MC -> game, seqlock.
#[repr(C)]
pub struct McStateHead {
	pub seq: u32,
	pub flags: u32,
	pub x: f64,
	pub y: f64,
	pub z: f64,
	pub yaw: f32,
	pub pitch: f32,
	pub eye_height: f32,
	pub sensitivity: f32, // Minecraft's mouse sensitivity option, 0..1
	pub teleport_ack: u32,
	pub gui_scale: u32,
	pub frame_counter: u64,
	pub fov_deg: f32, // effective vertical field of view
	pub bob_phase: f32,
	pub bob_amount: f32,
	pub pad: u32,
	pub eye: [f64; 3], // camera position, interpolated
	pub tick_qpc: i64,
	pub prev: [f64; 3],
	pub cur: [f64; 3],
	pub tick_floats: [f32; 7],
	pub tick_pad: u32,
	pub camera_mode: u32, // 0 first person, 1 behind, 2 in front
	pub camera_distance: f32,
}
const _: () = assert!(size_of::<McStateHead>() == 0xC8);

pub const MC_IN_WORLD: u32 = 1 << 0;
pub const MC_SCREEN_OPEN: u32 = 1 << 1;
pub const MC_ON_GROUND: u32 = 1 << 2;
pub const MC_SNEAKING: u32 = 1 << 3;
pub const MC_SPRINTING: u32 = 1 << 4;
pub const MC_DEAD: u32 = 1 << 5;
pub const MC_SWIMMING: u32 = 1 << 6;
pub const MC_FLYING: u32 = 1 << 7;

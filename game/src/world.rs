//! Where things are. Elden Ring keeps three position spaces that matter here:
//!
//! - havok: physics, raycasts and debug drawing. Meters, Y up. Its origin moves with streaming,
//!   so it is only good for this frame.
//! - block: relative to the map block (`m10_01_00_00`) the player is in. Every legacy dungeon and
//!   interior sits near (0, 0, 0) of its own block.
//! - chunk: the open world's global coordinates (the `m60`/`m61` tiles).
//!
//! "World" coordinates here are stable across frames: the open world uses chunk coordinates, and
//! every other block gets its own island far out in Minecraft's world so they never overlap.
//! Minecraft coordinates are world coordinates with Z flipped (Minecraft is right-handed).

use eldenring::cs::{BlockId, ChrInsExt, CSWorldGeomMan, FieldArea, GameMan, PlayerIns};
use fromsoftware_shared::FromStatic;

pub type V3 = [f64; 3];

/// Where the open world's Minecraft islands start, one per overworld area (m60, m61, ...).
const OVERWORLD_AREA_SPACING: f64 = 100_000.0;
/// Dungeons and interiors: far from the open world, spaced so no two can touch.
const ISLAND_ORIGIN: f64 = 3_000_000.0;
const ISLAND_AREA_SPACING: f64 = 40_000.0;
const ISLAND_REGION_SPACING: f64 = 8_000.0;
const ISLAND_BLOCK_SPACING: f64 = 20_000.0;
const ISLAND_INDEX_SPACING: f64 = 4_000.0;

/// This frame's mapping between havok space and Minecraft.
#[derive(Clone, Copy)]
pub struct Space {
	/// world - havok, from the player this frame.
	delta: V3,
	/// Which Minecraft "world" (collision epoch) the player is in: one per open-world area or block.
	pub world_id: u32,
	/// What it was learned from: the player's havok and world positions that frame.
	pub havok: V3,
	pub world: V3,
}

impl Space {
	/// None while the player is between maps (a loading screen's first frames report no block).
	pub fn of(player: &PlayerIns) -> Option<Self> {
		// PlayerIns::block_position belongs to PlayerIns::current_block_id. ChrIns's
		// resource/LOD block can remain unchanged while the player walks between tiles
		// or enters a connected interior; it is not this position's coordinate origin.
		let block = player.current_block_id;
		if i32::from(player.chr_ins.block_id()) == -1 {
			return None;
		}
		let world_id = collision_world(block)?;
		let havok = player.chr_ins.modules.physics.position;
		// The tile-local position is exact; the tile's place comes from its name.
		let b = player.block_position;
		if ![havok.0, havok.1, havok.2, b.x, b.y, b.z].iter().all(|v| v.is_finite()) {
			return None;
		}
		let o = if block.is_overworld() { tile_origin(block) } else { island(block) };
		let world = [b.x as f64 + o[0], b.y as f64 + o[1], b.z as f64 + o[2]];
		Some(Self {
			delta: [world[0] - havok.0 as f64, world[1] - havok.1 as f64, world[2] - havok.2 as f64],
			world_id,
			havok: [havok.0 as f64, havok.1 as f64, havok.2 as f64],
			world,
		})
	}

	/// How far apart (meters) two frames' havok-to-world offsets are.
	pub fn drift(&self, other: &Space) -> f64 {
		let d = [self.delta[0] - other.delta[0], self.delta[1] - other.delta[1], self.delta[2] - other.delta[2]];
		(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt()
	}

	pub fn havok_to_mc(&self, h: [f32; 3]) -> V3 {
		to_mc([h[0] as f64 + self.delta[0], h[1] as f64 + self.delta[1], h[2] as f64 + self.delta[2]])
	}

	pub fn mc_to_havok(&self, m: V3) -> [f32; 3] {
		let w = from_mc(m);
		[(w[0] - self.delta[0]) as f32, (w[1] - self.delta[1]) as f32, (w[2] - self.delta[2]) as f32]
	}
}

/// Overworld tiles share a collision world; independent interiors have their own epochs.
pub fn collision_world(block: BlockId) -> Option<u32> {
	if i32::from(block) == -1 {
		None
	} else if block.is_overworld() {
		Some(i32::from(BlockId::from_parts(block.area(), 0, 0, 0)) as u32)
	} else {
		Some(i32::from(block) as u32)
	}
}

/// Bounded, read-only map diagnostics, called by the existing two-second movement report.
/// A geometry container's presence/count is evidence of streaming progress, not a promise
/// that all its meshes/collision have finished loading. Nothing requests or bypasses a warp.
pub fn streaming_report(player: &PlayerIns, native_contact: bool, floor_gap: Option<f64>) -> String {
	let chr = &player.chr_ins;
	let h = chr.modules.physics.position;
	let local = player.block_position;
	let model = chr.chr_ctrl.model_matrix.3;
	let model_lag = ((model.0 - h.0).powi(2) + (model.1 - h.1).powi(2) + (model.2 - h.2).powi(2)).sqrt();
	let field = unsafe { FieldArea::instance() }.ok().map(|f| f.current_play_region_id);
	let warp = unsafe { GameMan::instance() }.ok().map(|g| (g.warp_requested, g.move_map_target));
	let geometry = unsafe { CSWorldGeomMan::instance() }.ok().map(|g| {
		let describe = |block| g.geom_block_data_by_id(&block).map(|b| (b.geom_ins_vector.len(), b.geometry_array_count));
		let interiors: Vec<_> = (&g.blocks).into_iter().take(256)
			.filter(|e| !e.first.is_overworld()).take(8)
			.map(|e| format!("{}:{}/{}", e.first, e.second.geom_ins_vector.len(), e.second.geometry_array_count))
			.collect();
		// The reported cave is m31_03_00_00. Include it even if the bounded container
		// list fills up, or its container is present before any meshes have appeared.
		let cave = BlockId::from_parts(31, 3, 0, 0);
		format!("position {:?}, body {:?}, Groveside {:?}, interior containers [{}]", describe(player.current_block_id), describe(chr.block_id()), describe(cave), interiors.join(", "))
	}).unwrap_or_else(|| "unavailable".into());
	format!("maps: position {}, body {} (raw {}, override {}), origin {} | region {} / field {:?} | local ({:.2}, {:.2}, {:.2}), havok ({:.2}, {:.2}, {:.2}) | model lag {:.2} m, omission {:?}, render {}, alpha {:.2} | terrain contact {}, floor gap {:?}, native ground {}/{}/{}, no_gravity {}, contact flags {:#04x} | warp {:?} | geometry {}",
		player.current_block_id, chr.block_id(), chr.block_id, chr.block_id_override, chr.block_id_origin(),
		player.play_region_id, field, local.x, local.y, local.z, h.0, h.1, h.2,
		model_lag, chr.omission_mode, chr.chr_flags1c5.enable_render(), chr.base_transparency,
		native_contact, floor_gap, chr.modules.physics.standing_on_solid_ground, chr.modules.physics.touching_solid_ground,
		chr.modules.physics.is_touching_ground, chr.chr_flags1c4.no_gravity(), chr.chr_flags1c6.0, warp, geometry)
}

/// Where an open-world tile's local coordinates start: m60_XX_ZZ_L tiles are 256 m across at
/// level 0 and double per level. Only the tile's name is used, so a place maps to the same
/// Minecraft coordinates every session. (ChrIns::chunk_position looked global but is relative to
/// another tile some frames, so it is not used.) Each overworld area gets its own island.
fn tile_origin(block: BlockId) -> V3 {
	let size = 256.0 * (1u32 << block.index().min(4)) as f64;
	let island = (block.area() as f64 - 60.0) * OVERWORLD_AREA_SPACING;
	[block.block() as f64 * size + island, 0.0, block.region() as f64 * size]
}

fn island(block: BlockId) -> V3 {
	[
		ISLAND_ORIGIN + block.area() as f64 * ISLAND_AREA_SPACING + block.region() as f64 * ISLAND_REGION_SPACING,
		0.0,
		ISLAND_ORIGIN + block.block() as f64 * ISLAND_BLOCK_SPACING + block.index() as f64 * ISLAND_INDEX_SPACING,
	]
}

/// World to Minecraft. FromSoftware's space is assumed left-handed, so Z flips. Directions use
/// the same mapping, so a mistake here mirrors the world rather than breaking it.
pub fn to_mc(w: V3) -> V3 {
	[w[0], w[1], -w[2]]
}

pub fn from_mc(m: V3) -> V3 {
	[m[0], m[1], -m[2]]
}

/// A havok-space direction in Minecraft axes (no translation).
pub fn dir_to_mc(d: [f32; 3]) -> V3 {
	to_mc([d[0] as f64, d[1] as f64, d[2] as f64])
}

/// Minecraft yaw and pitch (degrees) looking along a Minecraft-space direction.
pub fn yaw_pitch(d: V3) -> (f32, f32) {
	let len = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt().max(1e-9);
	let yaw = (-d[0]).atan2(d[2]).to_degrees();
	let pitch = -(d[1] / len).clamp(-1.0, 1.0).asin().to_degrees();
	(yaw as f32, pitch as f32)
}

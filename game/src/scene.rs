//! What the renderer draws, handed over from the game thread: Minecraft's block meshes and
//! texture atlas, the scanned collision (it hides blocks behind Elden Ring's terrain), the camera
//! and the HUD state. The renderer runs on the game's present, on another thread.

use std::collections::HashMap;
use std::sync::{Arc, LazyLock, Mutex};

use crate::proto::RenVertex;

pub struct Atlas {
	pub width: u32,
	pub height: u32,
	pub pixels: Vec<u8>, // RGBA8, top row first
}

/// The camera in Minecraft coordinates (the same one the game camera is set from).
#[derive(Clone, Copy)]
pub struct View {
	pub eye: [f64; 3],
	pub yaw: f32,
	pub pitch: f32,
	pub fov_deg: f32,
}

#[derive(Clone, Copy, Default)]
pub struct Hud {
	pub shown: bool,
	/// Minecraft's crosshair is up (first person, no screen open): draw its area inverted.
	pub crosshair: bool,
	pub gui_scale: u32,
	/// The mouse cursor over an open Minecraft screen (back buffer pixels).
	pub cursor: Option<(f32, f32)>,
}

/// Everything else Minecraft draws this frame (mobs, players, chests, particles): triangle
/// batches relative to `origin`, each with its texture (0: the block atlas).
pub struct Entities {
	pub origin: [f64; 3],
	pub batches: Vec<Batch>,
	pub verts: Vec<RenVertex>,
}

#[derive(Clone, Copy)]
pub struct Batch {
	pub texture: u32,
	pub first: u32,
	pub count: u32,
	pub translucent: bool,
}

#[derive(Default)]
pub struct Scene {
	/// Entity textures (skins, mob textures, ...) by Minecraft's id for them.
	pub textures: HashMap<u32, Arc<Atlas>>,
	pub entities: Option<Arc<Entities>>,
	/// Minecraft's player model (third person), relative to its feet; drawn at `avatar_at`.
	pub avatar: Option<Arc<Entities>>,
	pub avatar_at: Option<[f64; 3]>,
	pub atlas: Option<Arc<Atlas>>,
	/// Minecraft sections (16-block cubes) and their triangle lists.
	pub sections: HashMap<[i32; 3], Arc<Vec<RenVertex>>>,
	/// Scanned collision per 8-block column: triangle corners relative to (x0, 0, z0).
	pub occluders: HashMap<(i32, i32), Arc<Vec<[f32; 3]>>>,
	pub view: Option<View>,
	/// The game camera's aspect ratio (it letterboxes when the screen is wider than it supports).
	pub aspect: f32,
	pub hud: Hud,
	/// The light blocks and entities are shaded with (see `lighting`).
	pub light: crate::lighting::Light,
	/// Light blocks from the game's own picture (F4 switches this off for the clock's light alone).
	pub game_light: bool,
}

static SCENE: LazyLock<Mutex<Scene>> = LazyLock::new(|| Mutex::new(Scene::default()));

pub fn with<R>(f: impl FnOnce(&mut Scene) -> R) -> R {
	f(&mut SCENE.lock().unwrap_or_else(|e| e.into_inner()))
}

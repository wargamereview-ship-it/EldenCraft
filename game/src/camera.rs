//! Minecraft's camera in Elden Ring: its eye, yaw, pitch and field of view replace the game
//! camera's view every frame, right after the game computes its own (`DrawParamUpdate`).
//!
//! The view matrix's conventions (which way its forward and up rows point, and its handedness)
//! are read off the game's own matrix while it still orbits behind the player, so the rebuilt
//! matrix matches whatever the game uses.

use std::sync::Mutex;

use eldenring::cs::{CSCamera, WorldChrMan};
use fromsoftware_shared::{F32Vector4, FromStatic};

use crate::log;

/// What the camera should show, in havok space. None leaves the game's camera alone.
#[derive(Clone, Copy)]
pub struct View {
	pub eye: [f32; 3],
	pub yaw: f32,   // Minecraft degrees
	pub pitch: f32, // Minecraft degrees, + looks down
	pub fov_deg: f32,
	/// The same view in Minecraft coordinates, for the renderer.
	pub mc: crate::scene::View,
}

/// Views handed to the game, newest last: the game renders a frame with a camera it was given a
/// frame or two earlier, and the blocks have to be drawn with that same one.
static HISTORY: Mutex<std::collections::VecDeque<crate::scene::View>> = Mutex::new(std::collections::VecDeque::new());
/// How many views back the frame on screen was rendered with (F11 cycles it).
static DELAY: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(1);

pub fn cycle_delay() -> usize {
	let d = (DELAY.load(std::sync::atomic::Ordering::Relaxed) + 1) % 4;
	DELAY.store(d, std::sync::atomic::Ordering::Relaxed);
	d
}

/// The view the frame being presented was rendered with.
pub fn rendered_view() -> Option<crate::scene::View> {
	let history = HISTORY.lock().unwrap_or_else(|e| e.into_inner());
	let d = DELAY.load(std::sync::atomic::Ordering::Relaxed);
	history.len().checked_sub(1 + d).and_then(|i| history.get(i)).or(history.front()).copied()
}

struct Conventions {
	forward: f32,
	up: f32,
	hand: f32,
	logged: bool,
}

static VIEW: Mutex<Option<View>> = Mutex::new(None);
static CONVENTIONS: Mutex<Conventions> = Mutex::new(Conventions { forward: 1.0, up: 1.0, hand: 1.0, logged: false });

pub fn set(view: Option<View>) {
	*VIEW.lock().unwrap_or_else(|e| e.into_inner()) = view;
}

type V = [f32; 3];
fn dot(a: V, b: V) -> f32 {
	a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
fn cross(a: V, b: V) -> V {
	[a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}
fn norm(a: V) -> V {
	let l = dot(a, a).sqrt().max(1e-9);
	[a[0] / l, a[1] / l, a[2] / l]
}
fn row(v: &F32Vector4) -> V {
	[v.0, v.1, v.2]
}

/// The camera task: learn the game's conventions from its matrix, then replace it.
pub fn apply() {
	let Ok(camera) = (unsafe { CSCamera::instance_mut() }) else { return };
	let cam = camera.pers_cam_1.as_mut();
	let view = *VIEW.lock().unwrap_or_else(|e| e.into_inner());

	// The game's own camera orbits behind the player and looks at them.
	let m = &cam.matrix;
	let player = unsafe { WorldChrMan::instance() }.ok().and_then(|w| w.main_player.as_ref()).map(|p| {
		let h = p.chr_ins.modules.physics.position;
		[h.0, h.1 + 1.5, h.2]
	});
	let mut conv = CONVENTIONS.lock().unwrap_or_else(|e| e.into_inner());
	// Only the game's own camera can teach these signs. A Minecraft view (especially
	// during a jump or F5 orbit) may point away from ER's body and invert the calibration.
	if let Some(target) = player.filter(|_| view.is_none()) {
		let to = [target[0] - m.3.0, target[1] - m.3.1, target[2] - m.3.2];
		if dot(to, to) > 1.0 {
			conv.forward = if dot(row(&m.2), to) < 0.0 { -1.0 } else { 1.0 };
			conv.up = if m.1.1 < 0.0 { -1.0 } else { 1.0 };
			conv.hand = if dot(row(&m.0), cross(row(&m.1), row(&m.2))) < 0.0 { -1.0 } else { 1.0 };
			if !conv.logged {
				conv.logged = true;
				log::line(&format!("camera: forward row {:+}, up row {:+}, handedness {:+}, fov {:.3}", conv.forward, conv.up, conv.hand, cam.fov));
			}
		}
	}

	let aspect = cam.aspect_ratio;
	crate::scene::with(|s| s.aspect = aspect);
	let Some(v) = view else {
		HISTORY.lock().unwrap_or_else(|e| e.into_inner()).clear();
		return;
	};
	{
		let mut history = HISTORY.lock().unwrap_or_else(|e| e.into_inner());
		history.push_back(v.mc);
		while history.len() > 8 {
			history.pop_front();
		}
	}
	let (yaw, pitch) = (v.yaw.to_radians(), v.pitch.clamp(-89.9, 89.9).to_radians());
	// Minecraft's look direction, then into havok axes (Z flips).
	let f = [-yaw.sin() * pitch.cos(), -pitch.sin(), -(yaw.cos() * pitch.cos())];
	let up = norm([-f[1] * f[0], 1.0 - f[1] * f[1], -f[1] * f[2]]);
	let r2 = f.map(|c| c * conv.forward);
	let r1 = up.map(|c| c * conv.up);
	let r0 = norm(cross(r1, r2)).map(|c| c * conv.hand);
	cam.matrix.0 = F32Vector4(r0[0], r0[1], r0[2], 0.0);
	cam.matrix.1 = F32Vector4(r1[0], r1[1], r1[2], 0.0);
	cam.matrix.2 = F32Vector4(r2[0], r2[1], r2[2], 0.0);
	cam.matrix.3 = F32Vector4(v.eye[0], v.eye[1], v.eye[2], 1.0);
	// The game keeps its field of view in radians (vertical, like Minecraft's).
	if cam.fov > 0.1 && cam.fov < 3.0 && v.fov_deg > 1.0 {
		cam.fov = v.fov_deg.to_radians();
	}
}

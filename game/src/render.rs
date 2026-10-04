//! Minecraft's block meshes and atlas, read from the render ring and handed to the renderer
//! (`gpu`), and the collision debug view (F7, drawn with the game's debug drawer).

use std::sync::Arc;

use eldenring::cs::{CSEzDraw, EzDrawFillMode, RendMan};
use fromsoftware_shared::{F32Vector4, FromStatic, Triangle};

use crate::link::Link;
use crate::log;
use crate::proto::{self, ColTri, RenVertex};
use crate::scene::{self, Atlas};
use crate::world::{Space, V3};

/// Batches (u32 count, u32 vertices, {texture, first, count, flags}[]) then vertices, starting
/// at `at` in a scene or avatar message. None when there are no batches (nothing to draw).
fn parse_batches(payload: &[u8], at: usize, origin: [f64; 3]) -> Option<Arc<scene::Entities>> {
	let word = |o: usize| u32::from_le_bytes(payload[o..o + 4].try_into().unwrap()) as usize;
	let (batches, vertices) = (word(at), word(at + 4));
	if batches == 0 {
		return None;
	}
	let head = at + 8 + batches * 16;
	let body = payload.get(head..).unwrap_or(&[]);
	let vertices = vertices.min(body.len() / size_of::<RenVertex>());
	let batches = (0..batches)
		.filter_map(|i| {
			let b = at + 8 + i * 16;
			(b + 16 <= payload.len()).then(|| scene::Batch { texture: word(b) as u32, first: word(b + 4) as u32, count: word(b + 8) as u32, translucent: word(b + 12) & 1 != 0 })
		})
		.filter(|b| (b.first + b.count) as usize <= vertices)
		.collect();
	let verts = (0..vertices).map(|i| unsafe { body.as_ptr().cast::<RenVertex>().add(i).read_unaligned() }).collect();
	Some(Arc::new(scene::Entities { origin, batches, verts }))
}

/// Render ring bytes read per frame, so a burst of meshes doesn't stall a frame.
const READ_BUDGET: usize = 8 << 20;
/// Upper bound on debug triangles drawn per frame.
const MAX_TRIS: usize = 30_000;

pub struct Blocks {
	logged_atlas: bool,
}

impl Blocks {
	pub fn new() -> Self {
		Self { logged_atlas: false }
	}

	/// Takes in whatever Minecraft sent since the last frame.
	pub fn read(&mut self, link: &Link) {
		let logged = &mut self.logged_atlas;
		link.read_render(READ_BUDGET, |kind, payload| match kind {
			proto::REN_ATLAS if payload.len() >= 8 => {
				let w = u32::from_le_bytes(payload[0..4].try_into().unwrap());
				let h = u32::from_le_bytes(payload[4..8].try_into().unwrap());
				let pixels = payload[8..].to_vec();
				if pixels.len() as u64 == w as u64 * h as u64 * 4 {
					if !*logged {
						*logged = true;
						log::line(&format!("blocks: got Minecraft's {w}x{h} texture atlas"));
					}
					scene::with(|s| s.atlas = Some(Arc::new(Atlas { width: w, height: h, pixels })));
				}
			}
			proto::REN_SECTION if payload.len() >= 16 => {
				let key = [0, 4, 8].map(|o| i32::from_le_bytes(payload[o..o + 4].try_into().unwrap()));
				let count = u32::from_le_bytes(payload[12..16].try_into().unwrap()) as usize;
				let body = &payload[16..];
				let n = (count.min(body.len() / size_of::<RenVertex>()) / 3) * 3;
				let verts: Vec<RenVertex> = (0..n).map(|i| unsafe { body.as_ptr().cast::<RenVertex>().add(i).read_unaligned() }).collect();
				scene::with(|s| {
					if verts.is_empty() {
						s.sections.remove(&key);
					} else {
						s.sections.insert(key, Arc::new(verts));
					}
				});
			}
			proto::REN_CLEAR_ALL => scene::with(|s| s.sections.clear()),
			proto::REN_TEXTURE if payload.len() >= 16 => {
				let [id, w, h] = [0, 4, 8].map(|o| u32::from_le_bytes(payload[o..o + 4].try_into().unwrap()));
				let pixels = payload[16..].to_vec();
				if pixels.len() as u64 == w as u64 * h as u64 * 4 {
					scene::with(|s| s.textures.insert(id, Arc::new(Atlas { width: w, height: h, pixels })));
				}
			}
			proto::REN_AVATAR if payload.len() >= 8 => {
				let avatar = parse_batches(payload, 0, [0.0; 3]);
				scene::with(|s| s.avatar = avatar);
			}
			proto::REN_SCENE if payload.len() >= 32 => {
				let origin = [0, 8, 16].map(|o| f64::from_le_bytes(payload[o..o + 8].try_into().unwrap()));
				let entities = parse_batches(payload, 24, origin);
				scene::with(|s| s.entities = entities);
			}
			_ => {}
		});
	}
}

fn draw_tri(ez: &mut CSEzDraw, p: [[f32; 3]; 3]) {
	let v = |a: [f32; 3]| F32Vector4(a[0], a[1], a[2], 1.0);
	let e = |a: [f32; 3], b: [f32; 3]| F32Vector4(b[0] - a[0], b[1] - a[1], b[2] - a[2], 0.0);
	ez.draw_triangle(&Triangle { origin: v(p[0]), edge1: e(p[0], p[1]), edge2: e(p[0], p[2]) });
}

/// The collision Minecraft was sent, as a wireframe: floors green, walls and steep parts red.
pub fn draw_collision<'a>(space: &Space, tris: impl Iterator<Item = &'a ColTri>, feet: V3) {
	let Some(ez) = (unsafe { RendMan::instance_mut() }).ok().map(|r| r.debug_ez_draw.as_mut()) else {
		return;
	};
	ez.set_fill_mode(EzDrawFillMode::Wireframe);
	let floor = F32Vector4(0.2, 1.0, 0.3, 1.0);
	let wall = F32Vector4(1.0, 0.25, 0.2, 1.0);
	for (n, t) in tris.enumerate() {
		if n >= MAX_TRIS {
			break;
		}
		let c = [0, 3, 6].map(|k| [t.v[k] as f64, t.v[k + 1] as f64, t.v[k + 2] as f64]);
		if (c[0][0] - feet[0]).abs() > 12.0 || (c[0][2] - feet[2]).abs() > 12.0 || (c[0][1] - feet[1]).abs() > 8.0 {
			continue;
		}
		let n = normal_y(&c);
		ez.set_color(if n > 0.7 { &floor } else { &wall });
		draw_tri(ez, c.map(|v| space.mc_to_havok(v)));
	}
	ez.set_fill_mode(EzDrawFillMode::Fill);
}

fn normal_y(c: &[V3; 3]) -> f64 {
	let a = [c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2]];
	let b = [c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2]];
	let n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
	let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt().max(1e-9);
	(n[1] / len).abs()
}

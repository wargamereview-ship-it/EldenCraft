//! Minecraft's block meshes and atlas, read from the render ring and handed to the renderer
//! (`gpu`), and the collision debug view (F7, drawn with the game's debug drawer).

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use eldenring::cs::RendMan;
use eldenring::position::HavokPosition;
use fromsoftware_shared::{F32Vector4, FromStatic};

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

/// Horizontal / vertical reach of the debug view around the player (blocks).
const DRAW_RANGE: f64 = 8.0;
const DRAW_HEIGHT: f64 = 5.0;
/// The cached edge set is rebuilt after the player moves this far from where it was built;
/// it is built a little wider than the view so nothing pops in at the rim.
const DRAW_SLACK: f64 = 3.0;
/// Upper bound on debug lines drawn per frame.
const MAX_EDGES: usize = 4_000;
/// A changed scan rebuilds the cached edges at most this often (walking on still rebuilds at once).
const REBUILD_INTERVAL: std::time::Duration = std::time::Duration::from_millis(500);

/// The collision debug view (F7). Every drawn triangle is a native call, so the nearby edges are
/// gathered once and re-used until the scan changes or the player walks on; edges shared by
/// neighbouring triangles are drawn once.
#[derive(Default)]
pub struct CollisionDraw {
	generation: u64,
	built_at: Option<V3>,
	built_time: Option<std::time::Instant>,
	floors: Vec<([f32; 3], [f32; 3])>,
	walls: Vec<([f32; 3], [f32; 3])>,
}

impl CollisionDraw {
	/// Drop the cache (view switched off), so it is rebuilt fresh when shown again.
	pub fn reset(&mut self) {
		self.built_at = None;
	}

	fn stale(&self, generation: u64, feet: V3) -> bool {
		self.built_at.is_none_or(|at| (at[0] - feet[0]).hypot(at[2] - feet[2]) > DRAW_SLACK || (at[1] - feet[1]).abs() > DRAW_SLACK)
			|| (self.generation != generation && self.built_time.is_none_or(|t| t.elapsed() >= REBUILD_INTERVAL))
	}

	fn rebuild(&mut self, generation: u64, tris: &HashMap<(i32, i32), Vec<ColTri>>, feet: V3) {
		self.generation = generation;
		self.built_at = Some(feet);
		self.built_time = Some(std::time::Instant::now());
		self.floors.clear();
		self.walls.clear();
		let (range, height) = (DRAW_RANGE + DRAW_SLACK, DRAW_HEIGHT + DRAW_SLACK);
		let near = crate::scan::near_columns(feet, 2);
		// Edges already taken (quantised to a millimetre, ends ordered).
		let mut seen: HashSet<[i32; 6]> = HashSet::new();
		let q = |v: [f64; 3]| v.map(|c| (c * 1000.0).round() as i32);
		for t in tris.iter().filter(|(c, _)| near.contains(c)).flat_map(|(_, t)| t) {
			let c = [0, 3, 6].map(|k| [t.v[k] as f64, t.v[k + 1] as f64, t.v[k + 2] as f64]);
			if c.iter().all(|v| (v[0] - feet[0]).abs() > range || (v[2] - feet[2]).abs() > range)
				|| c.iter().all(|v| (v[1] - feet[1]).abs() > height) {
				continue;
			}
			let wall = normal_y(&c) <= 0.7;
			for (a, b) in [(0, 1), (1, 2), (2, 0)] {
				let (qa, qb) = (q(c[a]), q(c[b]));
				let (lo, hi) = if qa <= qb { (qa, qb) } else { (qb, qa) };
				let key = [lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]];
				let edge = (c[a].map(|v| v as f32), c[b].map(|v| v as f32));
				// A shared edge is drawn once, as whichever triangle reached it first.
				if seen.insert(key) {
					if wall { self.walls.push(edge) } else { self.floors.push(edge) }
				}
			}
		}
		// Every edge costs a native call per frame: keep the nearest ones and drop the rest.
		let dist = |e: &([f32; 3], [f32; 3])| (e.0[0] as f64 - feet[0]).powi(2) + (e.0[1] as f64 - feet[1]).powi(2) + (e.0[2] as f64 - feet[2]).powi(2);
		for edges in [&mut self.floors, &mut self.walls] {
			edges.sort_by(|a, b| dist(a).total_cmp(&dist(b)));
			edges.truncate(MAX_EDGES / 2);
		}
	}

	pub fn draw(&mut self, space: &Space, scanner: &crate::scan::Scanner, feet: V3) {
		if self.stale(scanner.generation, feet) {
			self.rebuild(scanner.generation, &scanner.tris, feet);
		}
		let Some(ez) = (unsafe { RendMan::instance_mut() }).ok().map(|r| r.debug_ez_draw.as_mut()) else {
			return;
		};
		let mut budget = MAX_EDGES;
		for (color, edges) in [(F32Vector4(0.2, 1.0, 0.3, 1.0), &self.floors), (F32Vector4(1.0, 0.25, 0.2, 1.0), &self.walls)] {
			ez.set_color(&color);
			for (a, b) in edges.iter().take(budget) {
				let [a, b] = [a, b].map(|v| space.mc_to_havok([v[0] as f64, v[1] as f64, v[2] as f64]));
				ez.draw_line(&HavokPosition::from_xyz(a[0], a[1], a[2]), &HavokPosition::from_xyz(b[0], b[1], b[2]));
			}
			budget = budget.saturating_sub(edges.len());
		}
	}
}

fn normal_y(c: &[V3; 3]) -> f64 {
	let a = [c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2]];
	let b = [c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2]];
	let n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
	let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt().max(1e-9);
	(n[1] / len).abs()
}

//! Elden Ring collision for Minecraft, found with the game's own physics raycasts.
//!
//! The world around the player is scanned in 8x8-block columns (Minecraft's collision regions)
//! on a half-block grid. The game's rays hit surfaces from either side, so one ray dropped from
//! high above, re-cast from just past each hit, finds a stack of surfaces. Terrain meshes can be
//! thin or overlap, so crossing parity is only an approximation of solid spans: discarding a
//! thin pair must never discard the collision surface itself. All crossings become smooth
//! floors/ceilings joined with neighbouring samples. Differences between guessed solid spans
//! only propose walls: short horizontal native rays must confirm each half-block height band.
//! A roof over an open passage cannot fill the passage with walls merely by changing parity.
//! Minecraft gets the triangles and a one-block-thick voxel skin under every top.

use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use eldenring::cs::{CSHavokMan, CSPhysWorld, PlayerIns, WorldChrMan};
use eldenring::position::{HavokPosition, PositionDelta};
use fromsoftware_shared::FromStatic;

use crate::link::{Link, bytes_of, bytes_of_slice};
use crate::log;
use crate::proto::{self, ColBlock, ColRegion, ColTri};
use crate::world::{Space, V3};

const REGION: i32 = 8;
const STEP: f64 = 0.5;
/// A native floor this far above MC's feet still counts as a slope step, not a crossed floor.
const FLOOR_TOLERANCE: f64 = 0.3;
const SAMPLES: usize = (REGION as f64 / STEP) as usize + 1; // 17: both edges of the column
/// Columns scanned in full (walls, half-block grid) around the player (radius, in regions).
const RADIUS: i32 = 3;
/// Columns scanned as floors only, out to this radius (in regions): a coarse ring that lets
/// Minecraft see distant ground without paying for walls.
const FAR_RADIUS: i32 = 10;
/// Sample spacing and samples per side of a far column.
const FAR_STEP: f64 = 1.0;
const FAR_SAMPLES: usize = (REGION as f64 / FAR_STEP) as usize + 1;
/// A far column is rescanned once the player is this far above or below where it was scanned.
const FAR_RESCAN_DY: f64 = 8.0;
/// Far columns that came back with missing samples (map still streaming in) are retried this
/// often, at most FAR_TRIES times in all: some ground is simply empty (water, void).
const FAR_RETRY: Duration = Duration::from_secs(10);
const FAR_TRIES: u8 = 3;
/// Rescan a column once the player is this far (blocks) above or below where it was scanned.
const RESCAN_DY: f64 = 4.0;
/// A scan during map loading may contain no hits. Retry incomplete nearby columns even
/// while the player's height is unchanged; an early empty scan is not permanent evidence.
const RETRY_INCOMPLETE: Duration = Duration::from_secs(2);
/// Vertical reach of one scan, relative to the player's feet: what gets turned into collision.
const UP_REACH: f64 = 24.0;
const DOWN_REACH: f64 = 24.0;
/// Rays start this far above the feet, in open sky, so the first surface they cross is always
/// one they enter (no guessing whether they begin inside something).
const SKY: f64 = 500.0;
/// Surfaces crossed per sample at most.
const MAX_CROSSINGS: usize = 16;
/// Re-cast this far past each hit.
const PAST_HIT: f64 = 0.01;
/// Air gaps thinner than this are merged when approximating volumes for walls and voxels.
const MIN_SPAN: f64 = 0.08;
/// Resolve proposed walls at the same height resolution as the horizontal sampling grid.
const WALL_BAND: f64 = STEP;
/// Merge adjacent confirmed bands only if the native wall positions also agree.
const WALL_JOIN: f64 = 0.04;
/// Neighbouring tops (or undersides) closer than this in height are one smooth surface; further
/// apart, there is a wall between them.
const MAX_JOIN: f64 = 1.25;
/// The voxel skin reaches this far under every top.
const SKIN: f64 = 1.0;
/// Time per frame spent casting rays.
const BUDGET: Duration = Duration::from_micros(2500);
/// Right after a map change or a refresh, Minecraft holds still until the columns around the player
/// arrive, so they are scanned with a larger budget until they are all in (at most HURRY_FOR).
const HURRY_BUDGET: Duration = Duration::from_millis(9);
const HURRY_FOR: Duration = Duration::from_secs(3);

/// Filters that see ground, walls and rock but not bushes (found with the F6 aim probe in
/// Limgrave; 0 and the single-bit masks see foliage too). The first that also looks through the
/// player's own body (tested at start-up, straight down onto the player) is used.
const PREFERRED_FILTERS: [u32; 7] = [0x2, 0x3, 0x4, 0xb, 0xc, 0x3a, 0x3f];
/// The player's own body as the rays see it (every filter does): samples this close to its
/// feet are not scanned.
const BODY_RADIUS: f64 = 0.75;

/// Candidate filters tried until one hits the floor under the player.
fn filter_candidates() -> Vec<u32> {
	let mut v: Vec<u32> = PREFERRED_FILTERS.to_vec();
	v.extend(0..64);
	v.extend((6..32).map(|n| 1u32 << n));
	v.extend([0x7FFF_FFFF, 0xFFFF_FFFF]);
	v
}

/// Solid spans at one sample, (bottom, top), highest first. A bottom of NEG_INFINITY means
/// solid all the way down (terrain).
#[derive(Clone, Default)]
struct Sample {
	solids: Vec<(f64, f64)>,
	/// Actual ray hits, independent of guessed entry/exit pairs. Thin terrain is still a floor.
	surfaces: Vec<f64>,
}

fn interpolate([a, b, c, d]: [f64; 4], tx: f64, tz: f64) -> f64 {
	(a * (1.0 - tx) + b * tx) * (1.0 - tz) + (c * (1.0 - tx) + d * tx) * tz
}

fn buried_top(solids: &[(f64, f64)], y: f64, scan_y: f64) -> Option<f64> {
	// A finite pair may be a roof and a floor with air between, not a closed solid.
	// Only the bottom terrain tail near the original scan height is a recovery floor.
	// Native ground patches and destination sweeps still catch downward floor crossings.
	solids.iter().find(|(lo, hi)| *lo == f64::NEG_INFINITY && *hi <= scan_y + 1.0
		&& y < hi - 0.6).map(|(_, hi)| *hi)
}

impl Sample {
	fn tops(&self) -> impl Iterator<Item = f64> + '_ {
		self.solids.iter().map(|s| s.1)
	}

	/// The floor the player would stand on near `y`: the highest top at or below y + 1.
	fn floor_near(&self, y: f64) -> Option<f64> {
		self.surfaces.iter().copied().filter(|t| *t <= y + 1.0)
			.fold(None, |best, t| Some(best.map_or(t, |b: f64| b.max(t))))
	}
}

/// A proposed wall between adjacent vertical rays. Its volume parity is untrusted.
struct WallProbe { mid: (f64, f64), along_x: bool, lo: f64, hi: f64 }
/// A run of bands whose two endpoints were both hit by native rays. Endpoints follow the
/// actual wall angle instead of extruding a grid-aligned strip into an open passage.
struct WallFace { p0: (f64, f64), p1: (f64, f64), lo: f64, hi: f64 }

/// A column being scanned, one sample / wall band at a time.
struct Job {
	rx: i32,
	rz: i32,
	base_y: f64,
	/// Floor-only coarse scan: no walls, FAR_STEP spacing.
	far: bool,
	step: f64,
	n: usize,
	samples: Vec<Sample>,
	/// Samples that were inside the player's own body when scanned (the rays see it).
	in_body: Vec<bool>,
	next_sample: usize,
	walls: Option<Vec<WallProbe>>,
	next_wall: usize,
	next_band: usize,
	wall_run: Vec<WallFace>,
	wall_tris: Vec<ColTri>,
	confirmed_bands: u64,
	rejected_bands: u64,
}

impl Job {
	fn new(rx: i32, rz: i32, base_y: f64, far: bool) -> Self {
		let (step, n) = if far { (FAR_STEP, FAR_SAMPLES) } else { (STEP, SAMPLES) };
		Self {
			rx,
			rz,
			base_y,
			far,
			step,
			n,
			samples: vec![Sample::default(); n * n],
			in_body: vec![false; n * n],
			next_sample: 0,
			walls: None,
			next_wall: 0,
			next_band: 0,
			wall_run: Vec::new(),
			wall_tris: Vec::new(),
			confirmed_bands: 0,
			rejected_bands: 0,
		}
	}
}

struct ScannedColumn {
	base_y: f64,
	at: Instant,
	incomplete: bool,
	far: bool,
	/// Rescans since it last came back complete.
	tries: u8,
}

pub struct Scanner {
	filter: Option<u32>,
	/// Every filter that saw the floor in the probe, for the F6 aim probe.
	floor_filters: Vec<u32>,
	next_probe: Instant,
	epoch: u32,
	world_id: u32,
	/// Columns sent this epoch, with their height, age and missing-sample status.
	done: HashMap<(i32, i32), ScannedColumn>,
	job: Option<Job>,
	/// Triangles per column, kept for the debug view.
	pub tris: HashMap<(i32, i32), Vec<ColTri>>,
	/// Bumped whenever `tris` changes, so the debug view knows when to rebuild.
	pub generation: u64,
	/// Keep the full solid spans, including ground above a player who has fallen into it.
	/// A single floor chosen at scan height loses that evidence as the body descends.
	samples: HashMap<(i32, i32), Vec<Sample>>,
	rays: u64,
	confirmed_bands: u64,
	rejected_bands: u64,
	ray_time: Duration,
	next_report: Instant,
	hurry_until: Instant,
}

impl Scanner {
	pub fn new() -> Self {
		Self {
			filter: None,
			floor_filters: Vec::new(),
			next_probe: Instant::now(),
			epoch: 0,
			world_id: u32::MAX,
			done: HashMap::new(),
			job: None,
			tris: HashMap::new(),
			generation: 0,
			samples: HashMap::new(),
			rays: 0,
			confirmed_bands: 0,
			rejected_bands: 0,
			ray_time: Duration::ZERO,
			next_report: Instant::now(),
			hurry_until: Instant::now(),
		}
	}

	pub fn epoch(&self) -> u32 {
		self.epoch
	}

	/// A grace respawn can keep the map ID while replacing its loaded physics. Start a new
	/// collision epoch on the next step so old/empty columns cannot authorize the handoff.
	pub fn reset_after_load(&mut self) {
		self.world_id = u32::MAX;
		self.filter = None;
		self.floor_filters.clear();
		self.next_probe = Instant::now();
	}

	/// Refresh collision around a recovered player, keeping the existing surfaces until their
	/// replacements arrive. A missing or stale column must not keep causing the same fall.
	pub fn refresh_near(&mut self, feet: V3) {
		let near = near_columns(feet, 1);
		self.done.retain(|column, _| !near.contains(column));
		self.hurry_until = Instant::now() + HURRY_FOR;
		// Prioritize the destination's own column at its safe height, rather than scanning
		// around the body's still-below-ground position before the teleport is acknowledged.
		self.job = Some(Job::new(
			(feet[0].floor() as i32).div_euclid(REGION),
			(feet[2].floor() as i32).div_euclid(REGION),
			feet[1],
			false,
		));
	}

	/// F6: how far a ray along `dir` from `from` gets with each filter that sees the floor. Aimed
	/// at a bush and then at a wall, it shows which filter ignores foliage.
	pub fn aim_probe(&self, player: &PlayerIns, space: &Space, from: V3, dir: V3) -> String {
		let Some(world) = (unsafe { CSHavokMan::instance() }).ok().map(|h| &*h.phys_world) else {
			return "no physics world".into();
		};
		let reach = 30.0;
		let delta = dir.map(|d| d * reach);
		let mut out = Vec::new();
		for &f in &self.floor_filters {
			let mut rays = Rays { world, player, space, filter: f, count: 0, bodies: Vec::new() };
			let d = rays.cast(from, delta).map(|h| ((h[0] - from[0]).powi(2) + (h[1] - from[1]).powi(2) + (h[2] - from[2]).powi(2)).sqrt());
			out.push(match d {
				Some(d) => format!("{f:#x}@{d:.2}"),
				None => format!("{f:#x}@-"),
			});
		}
		out.join(" ")
	}

	/// F6: the solid spans straight under `feet`, scanned now, and the crossings behind them.
	pub fn column_probe(&self, player: &PlayerIns, space: &Space, feet: V3) -> String {
		let (Some(world), Some(filter)) = ((unsafe { CSHavokMan::instance() }).ok().map(|h| &*h.phys_world), self.filter) else {
			return "no physics world or filter yet".into();
		};
		let mut rays = Rays { world, player, space, filter, count: 0, bodies: Vec::new() };
		let (solids, hits) = sample_spans(&mut rays, feet[0], feet[2], feet[1]);
		let rel = |v: f64| if v.is_finite() { format!("{:+.2}", v - feet[1]) } else { "-inf".into() };
		format!(
			"crossings {} | solid {}",
			hits.iter().map(|h| rel(*h)).collect::<Vec<_>>().join(" "),
			solids.iter().map(|(lo, hi)| format!("[{}..{}]", rel(*lo), rel(*hi))).collect::<Vec<_>>().join(" ")
		)
	}

	/// The scanned floor at (x, z), interpolated between samples, if that column has been sent.
	pub fn floor_at(&self, x: f64, z: f64) -> Option<f64> {
		let rx = (x.floor() as i32).div_euclid(REGION);
		let rz = (z.floor() as i32).div_euclid(REGION);
		let base_y = self.done.get(&(rx, rz))?.base_y;
		let (quad, tx, tz) = self.sample_quad(x, z)?;
		let heights = quad.map(|sample| sample.floor_near(base_y));
		let [Some(a), Some(b), Some(c), Some(d)] = heights else { return None };
		Some(interpolate([a, b, c, d], tx, tz))
	}

	/// A terrain floor above buried feet. Finite guessed spans between roofs and floors
	/// cannot trigger recovery; they may enclose an open passage.
	pub fn buried_floor(&self, feet: V3) -> Option<f64> {
		let column = ((feet[0].floor() as i32).div_euclid(REGION), (feet[2].floor() as i32).div_euclid(REGION));
		let scan_y = self.done.get(&column)?.base_y;
		let (quad, tx, tz) = self.sample_quad(feet[0], feet[2])?;
		let heights = quad.map(|sample| buried_top(&sample.solids, feet[1], scan_y));
		let [Some(a), Some(b), Some(c), Some(d)] = heights else { return None };
		let heights = [a, b, c, d];
		if heights.iter().copied().fold(f64::NEG_INFINITY, f64::max)
			- heights.iter().copied().fold(f64::INFINITY, f64::min) > MAX_JOIN {
			return None; // A cliff or different surfaces, not one floor over the footprint.
		}
		let floor = interpolate(heights, tx, tz);
		(floor - feet[1] > 0.6).then_some(floor)
	}

	fn sample_quad(&self, x: f64, z: f64) -> Option<([&Sample; 4], f64, f64)> {
		let rx = (x.floor() as i32).div_euclid(REGION);
		let rz = (z.floor() as i32).div_euclid(REGION);
		let samples = self.samples.get(&(rx, rz))?;
		let fx = ((x - (rx * REGION) as f64) / STEP).clamp(0.0, (SAMPLES - 1) as f64);
		let fz = ((z - (rz * REGION) as f64) / STEP).clamp(0.0, (SAMPLES - 1) as f64);
		let (i, j) = ((fx as usize).min(SAMPLES - 2), (fz as usize).min(SAMPLES - 2));
		let (tx, tz) = (fx - i as f64, fz - j as f64);
		let at = |i: usize, j: usize| &samples[j * SAMPLES + i];
		Some(([at(i, j), at(i + 1, j), at(i, j + 1), at(i + 1, j + 1)], tx, tz))
	}

	/// One ray against Elden Ring's level geometry (Minecraft coords), once the filter is known.
	pub fn ray(&self, player: &PlayerIns, space: &Space, from: V3, delta: V3) -> Option<V3> {
		let world = unsafe { CSHavokMan::instance() }.ok().map(|h| &*h.phys_world)?;
		Rays { world, player, space, filter: self.filter?, count: 0, bodies: Vec::new() }.cast(from, delta)
	}

	/// True when Minecraft can ride this floor up by itself (SkyCollider's carry): the live
	/// floor under `feet` is flat and risen a little above them. A teleport recovery would only
	/// park Minecraft while a lift keeps rising, so these are left to the carry.
	pub fn carried_rise(&self, player: &PlayerIns, space: &Space, feet: V3) -> bool {
		const CARRY: f64 = 1.0;
		const FLAT: f64 = 0.15;
		let Some((_, _, h)) = self.ground_patch(player, space, feet) else { return false };
		let (lo, hi) = (h.iter().copied().fold(f64::INFINITY, f64::min), h.iter().copied().fold(f64::NEG_INFINITY, f64::max));
		hi - lo <= FLAT && h[0] - feet[1] > 0.02 && h[0] - feet[1] <= CARRY
	}

	/// Sweep downward from the last accepted feet to the new destination. This uses native
	/// surfaces directly: no streamed-region availability or solid-volume parity is required.
	/// Starting close to the feet also avoids roofs far above the player. Character hits are
	/// skipped, rather than accepting the player's capsule as a floor.
	pub fn crossed_floor(&self, player: &PlayerIns, space: &Space, from: V3, to: V3) -> Option<f64> {
		if to[1] > from[1] + 0.02 || (from[0] - to[0]).hypot(from[2] - to[2]) > 2.0 {
			return None; // Upward movement or travel is not a downward terrain crossing.
		}
		let world = unsafe { CSHavokMan::instance() }.ok().map(|h| &*h.phys_world)?;
		let mut rays = Rays { world, player, space, filter: self.filter?, count: 0, bodies: bodies(player, space) };
		let top = from[1] + 0.35;
		let bottom = to[1] - 0.1;
		let mut floor: Option<f64> = None;
		for [dx, dz] in [[0.0, 0.0], [-0.15, 0.0], [0.15, 0.0], [0.0, -0.15], [0.0, 0.15]] {
			let (x, z) = (to[0] + dx, to[2] + dz);
			if let Some(risen) = rays.risen_floor(x, z, to[1]) {
				if risen > to[1] + FLOOR_TOLERANCE {
					floor = Some(floor.map_or(risen, |f| f.max(risen)));
				}
				continue;
			}
			let mut y = top;
			for _ in 0..8 {
				if y <= bottom { break }
				let Some(hit) = rays.cast([x, y, z], [0.0, bottom - y, 0.0]) else { break };
				if hit[1] > y + 0.01 || hit[1] < bottom - 0.01 { break }
				let body = rays.bodies.iter().find(|b| (x - b[0]).hypot(z - b[2]) < BODY_RADIUS
					&& hit[1] > b[1] + 0.5 && hit[1] < b[1] + 3.0);
				if let Some(body) = body {
					y = (hit[1] - PAST_HIT).min(body[1] + 0.05);
					continue;
				}
				// Allow the centimetre-scale differences of interpolated feet and sampled slopes.
				if hit[1] > to[1] + FLOOR_TOLERANCE {
					floor = Some(floor.map_or(hit[1], |f| f.max(hit[1])));
				}
				break;
			}
		}
		floor
	}

	/// Small live surface around MC's feet. Column scans replace samples covered by bodies
	/// with neighbours, which can be several decimetres lower on slopes. These close rays
	/// give the player an accurate local floor without replacing walls or distant terrain.
	pub fn ground_patch(&self, player: &PlayerIns, space: &Space, feet: V3)
		-> Option<([f64; 2], f64, [f64; 5])> {
		if !feet.iter().all(|v| v.is_finite()) { return None; }
		let world = unsafe { CSHavokMan::instance() }.ok().map(|h| &*h.phys_world)?;
		let mut rays = Rays { world, player, space, filter: self.filter?, count: 0, bodies: bodies(player, space) };
		let radius = 0.4;
		let mut heights = [0.0; 5];
		for (i, [dx, dz]) in [[0.0, 0.0], [-radius, -radius], [radius, -radius], [radius, radius], [-radius, radius]].into_iter().enumerate() {
			let (x, z) = (feet[0] + dx, feet[2] + dz);
			let bottom = feet[1] - 1.5;
			let mut y = feet[1] + 0.35;
			let mut found = rays.risen_floor(x, z, feet[1]);
			if found.is_some() { y = bottom; }
			for _ in 0..8 {
				if y <= bottom { break; }
				let hit = rays.cast([x, y, z], [0.0, bottom - y, 0.0])?;
				if !hit[1].is_finite() || hit[1] > y + 0.01 || hit[1] < bottom - 0.01 { return None; }
				if let Some(body) = rays.bodies.iter().find(|b| (x - b[0]).hypot(z - b[2]) < BODY_RADIUS
					&& hit[1] > b[1] + 0.5 && hit[1] < b[1] + 3.0) {
					y = (hit[1] - PAST_HIT).min(body[1] + 0.05);
					continue;
				}
				found = Some(hit[1]);
				break;
			}
			heights[i] = found?;
		}
		let lo = heights.iter().copied().fold(f64::INFINITY, f64::min);
		let hi = heights.iter().copied().fold(f64::NEG_INFINITY, f64::max);
		if hi - lo > 0.8 { return None; } // A cliff/ledge is not a continuous floor patch.
		Some(([feet[0], feet[2]], radius, heights))
	}

	pub fn step(&mut self, link: &Link, player: &PlayerIns, space: &Space, feet: V3) {
		if space.world_id != self.world_id && self.world_id != u32::MAX {
			// A connected map change (walking through a cave mouth): the coordinate ranges of the maps
			// do not overlap, so what is already scanned stays valid. Clearing it made every crossing
			// stall until the terrain around the player was scanned and sent again.
			self.world_id = space.world_id;
			self.job = None;
			self.samples.clear();
			self.hurry_until = Instant::now() + HURRY_FOR;
			log::line(&format!("collision: connected change to world {:08x}; keeping {} scanned columns", space.world_id, self.done.len()));
		}
		if space.world_id != self.world_id {
			self.world_id = space.world_id;
			self.epoch = self.epoch.wrapping_add(1);
			self.done.clear();
			self.tris.clear();
			self.generation += 1;
			self.samples.clear();
			self.job = None;
			self.hurry_until = Instant::now() + HURRY_FOR;
			crate::scene::with(|s| s.occluders.clear());
			link.write_collision(proto::COL_CLEAR, &[bytes_of(&self.epoch)]);
			log::line(&format!("collision: new world {:08x}, epoch {}", space.world_id, self.epoch));
		}
		let Some(world) = (unsafe { CSHavokMan::instance() }).ok().map(|h| &*h.phys_world) else {
			return;
		};
		let mut rays = Rays { world, player, space, filter: 0, count: 0, bodies: bodies(player, space) };
		let Some(filter) = self.filter_or_probe(&mut rays, feet) else {
			return;
		};
		rays.filter = filter;

		let started = Instant::now();
		let budget = if started < self.hurry_until
			&& near_columns(feet, 1).iter().any(|c| self.done.get(c).is_none_or(|d| d.far)) { HURRY_BUDGET } else { BUDGET };
		while started.elapsed() < budget {
			if self.job.is_none() {
				match self.next_column(feet) {
					Some((rx, rz, far)) => self.job = Some(Job::new(rx, rz, feet[1], far)),
					None => break,
				}
			}
			let job = self.job.as_mut().unwrap();
			if run_job(job, &mut rays, started, budget) {
				let job = self.job.take().unwrap();
				if self.send(link, &job) {
					self.confirmed_bands += job.confirmed_bands;
					self.rejected_bands += job.rejected_bands;
					let incomplete = job.samples.iter().any(|s| s.surfaces.is_empty());
					let tries = self.done.get(&(job.rx, job.rz))
						.filter(|c| c.incomplete && c.far == job.far).map_or(0, |c| c.tries.saturating_add(1));
					self.done.insert((job.rx, job.rz), ScannedColumn {
						base_y: job.base_y,
						at: Instant::now(),
						incomplete,
						far: job.far,
						tries,
					});
				}
			}
		}
		self.rays += rays.count;
		self.ray_time += started.elapsed();

		let now = Instant::now();
		if now >= self.next_report && self.rays > 0 {
			self.next_report = now + Duration::from_secs(10);
			log::line(&format!(
				"collision: {} columns sent, {} rays at {:.1} us each; fitted wall bands {} native hits / {} rejected guesses",
				self.done.len(),
				self.rays,
				self.ray_time.as_secs_f64() * 1e6 / self.rays as f64,
				self.confirmed_bands, self.rejected_bands
			));
		}
	}

	/// The nearest column that is missing or was scanned at a very different height, and whether
	/// it is only in the coarse far ring. Columns within RADIUS (full scans) always come first.
	fn next_column(&self, feet: V3) -> Option<(i32, i32, bool)> {
		let prx = (feet[0].floor() as i32).div_euclid(REGION);
		let prz = (feet[2].floor() as i32).div_euclid(REGION);
		let mut best: Option<((i32, i32, bool), f64)> = None;
		for rx in prx - FAR_RADIUS..=prx + FAR_RADIUS {
			for rz in prz - FAR_RADIUS..=prz + FAR_RADIUS {
				let (dx, dz) = ((rx - prx).abs(), (rz - prz).abs());
				let far = dx.max(dz) > RADIUS;
				if let Some(c) = self.done.get(&(rx, rz)) {
					let stale = if far {
						// A full scan already covers a column that has drifted into the far ring.
						c.far && ((c.base_y - feet[1]).abs() >= FAR_RESCAN_DY
							|| (c.incomplete && c.tries < FAR_TRIES && c.at.elapsed() >= FAR_RETRY))
					} else {
						c.far || (c.base_y - feet[1]).abs() >= RESCAN_DY
							|| (c.incomplete && dx <= 1 && dz <= 1 && c.at.elapsed() >= RETRY_INCOMPLETE)
					};
					if !stale {
						continue;
					}
				}
				let cx = (rx * REGION) as f64 + REGION as f64 / 2.0 - feet[0];
				let cz = (rz * REGION) as f64 + REGION as f64 / 2.0 - feet[2];
				let d = cx * cx + cz * cz + if far { 1e9 } else { 0.0 };
				if best.is_none_or(|(_, b)| d < b) {
					best = Some(((rx, rz, far), d));
				}
			}
		}
		best.map(|(c, _)| c)
	}

	/// Finds which raycast filter sees the level geometry: the one that hits just under the feet.
	fn filter_or_probe(&mut self, rays: &mut Rays, feet: V3) -> Option<u32> {
		if self.filter.is_some() || Instant::now() < self.next_probe {
			return self.filter;
		}
		self.next_probe = Instant::now() + Duration::from_secs(5);
		let from = [feet[0], feet[1] + 1.0, feet[2]];
		let mut hits = Vec::new();
		for f in filter_candidates() {
			rays.filter = f;
			if let Some(y) = rays.cast(from, [0.0, -3.0, 0.0]).map(|h| h[1]) {
				hits.push(format!("{f:#x}@{:+.2}", y - feet[1]));
				if (y - feet[1]).abs() < 0.5 {
					self.floor_filters.push(f);
				}
				if self.filter.is_none() && (y - feet[1]).abs() < 0.5 {
					self.filter = Some(f);
				}
			}
		}
		log::line(&format!("collision: filter probe hits (height vs feet): {}", if hits.is_empty() { "none".into() } else { hits.join(" ") }));
		// From above, a filter that sees characters stops on the player's head instead of the floor.
		let above = [feet[0], feet[1] + 3.0, feet[2]];
		let mut body = Vec::new();
		for f in PREFERRED_FILTERS {
			rays.filter = f;
			let y = rays.cast(above, [0.0, -4.0, 0.0]).map(|h| h[1] - feet[1]);
			body.push(format!("{f:#x}@{}", y.map_or("-".into(), |y| format!("{y:+.2}"))));
			if y.is_some_and(|y| y.abs() < 0.5) && self.floor_filters.contains(&f) {
				self.filter = Some(f);
				break;
			}
		}
		log::line(&format!("collision: from above the player: {} (a hit near +1.8 is the player's own body)", body.join(" ")));
		if let Some(f) = self.filter {
			// How rays behave from inside geometry decides how walls and ceilings can be read.
			rays.filter = f;
			let below = [feet[0], feet[1] - 0.3, feet[2]];
			let up_from_below = rays.cast(below, [0.0, 1.0, 0.0]).map(|h| h[1] - feet[1]);
			let down_from_below = rays.cast(below, [0.0, -3.0, 0.0]).map(|h| h[1] - feet[1]);
			let ceiling = rays.cast([feet[0], feet[1] + 0.3, feet[2]], [0.0, 30.0, 0.0]).map(|h| h[1] - feet[1]);
			log::line(&format!(
				"collision: using filter {f:#x}; from 0.3 under the floor: up {up_from_below:?}, down {down_from_below:?}; ceiling {ceiling:?}"
			));
		}
		self.filter
	}

	/// Sends a finished column: triangles per 8-block layer, then the voxel skin for the column.
	fn send(&mut self, link: &Link, job: &Job) -> bool {
		let (tris, blocks) = build(job);
		let x0 = job.rx * REGION;
		let z0 = job.rz * REGION;
		let y_lo = (job.base_y - DOWN_REACH).floor() as i32;
		let y_hi = (job.base_y + UP_REACH).ceil() as i32;
		let mut layers: HashMap<i32, Vec<ColTri>> = HashMap::new();
		for t in &tris {
			let min_y = t.v[1].min(t.v[4]).min(t.v[7]);
			let max_y = t.v[1].max(t.v[4]).max(t.v[7]);
			// Minecraft only queries the layers overlapping its body. A tall wall stored in
			// its lowest layer is otherwise invisible at its top. Include every touched layer.
			for ry in (min_y.floor() as i32).div_euclid(REGION)..=(max_y.floor() as i32).div_euclid(REGION) {
				layers.entry(ry).or_default().push(*t);
			}
		}
		let (ry_lo, ry_hi) = (y_lo.div_euclid(REGION), y_hi.div_euclid(REGION));
		let ry_range = layers.keys().copied().chain([ry_lo, ry_hi]);
		let (ry_min, ry_max) = (ry_range.clone().min().unwrap(), ry_range.max().unwrap());
		for ry in ry_min..=ry_max {
			let layer = layers.get(&ry).map(Vec::as_slice).unwrap_or(&[]);
			let header = ColRegion {
				min: [x0, ry * REGION, z0],
				max: [x0 + REGION - 1, ry * REGION + REGION - 1, z0 + REGION - 1],
				epoch: self.epoch,
				count: layer.len() as u32,
			};
			if !link.write_collision(proto::COL_TRIS, &[bytes_of(&header), bytes_of_slice(layer)]) {
				return false;
			}
		}
		let header = ColRegion {
			min: [x0, ry_min * REGION, z0],
			max: [x0 + REGION - 1, ry_max * REGION + REGION - 1, z0 + REGION - 1],
			epoch: self.epoch,
			count: blocks.len() as u32,
		};
		if !link.write_collision(proto::COL_REGION, &[bytes_of(&header), bytes_of_slice(&blocks)]) {
			return false;
		}
		let all = tris;
		if job.far {
			// Coarse samples must not feed the half-block floor lookups, and the coarse mesh
			// is too rough to hide Minecraft blocks behind: only full scans do either.
			self.samples.remove(&(job.rx, job.rz));
		} else {
			self.samples.insert((job.rx, job.rz), job.samples.clone());
			// For the renderer: the same triangles hide Minecraft blocks behind Elden Ring's ground.
			let (ox, oz) = (x0 as f32, z0 as f32);
			let corners: Vec<[f32; 3]> = all.iter().flat_map(|t| [0, 3, 6].map(|k| [t.v[k] - ox, t.v[k + 1], t.v[k + 2] - oz])).collect();
			crate::scene::with(|s| s.occluders.insert((job.rx, job.rz), std::sync::Arc::new(corners)));
		}
		self.tris.insert((job.rx, job.rz), all);
		self.generation += 1;
		true
	}
}

struct Rays<'a> {
	world: &'a CSPhysWorld,
	player: &'a PlayerIns,
	space: &'a Space,
	filter: u32,
	count: u64,
	/// Characters' feet to keep out of the scan (filled once per frame).
	bodies: Vec<V3>,
}

/// A lift can rise a few tenths of a block between ticks. Floor probes that start only 0.35 above
/// the feet then begin underneath its top, and the player sinks through it. A separate probe
/// looks for such a risen floor between these heights over the feet.
const RISE_FREE: f64 = 0.36;
const RISE: f64 = 1.0;
/// Clear space needed above a risen surface for it to count as a floor, not a ceiling.
const HEADROOM: f64 = 1.6;

/// Characters this close to the player are kept out of the scan.
const BODY_RANGE: f32 = 48.0;

/// Feet (Minecraft coords) of the player and every character near it this frame: the rays see
/// their bodies, which must not become collision.
fn bodies(player: &PlayerIns, space: &Space) -> Vec<V3> {
	let at = |h: eldenring::position::HavokPosition| space.havok_to_mc([h.0, h.1, h.2]);
	let mut out = vec![at(player.chr_ins.modules.physics.position)];
	if let Ok(world) = unsafe { WorldChrMan::instance() } {
		for entry in world.chr_inses_by_distance.iter().filter(|e| e.distance < BODY_RANGE) {
			let chr = unsafe { entry.chr_ins.as_ref() };
			out.push(at(chr.modules.physics.position));
		}
	}
	out
}

impl Rays<'_> {
	/// A floor risen above the feet at (x, z): the first hit between RISE_FREE and RISE over
	/// `feet_y`, with room above it. Without the room it is a ceiling's underside, not a lift.
	/// The ray starts inside the player's own capsule, which it cannot hit above its base, so only
	/// other characters' capsules are excluded from the floor; every capsule is skipped over
	/// when checking the room above.
	fn risen_floor(&mut self, x: f64, z: f64, feet_y: f64) -> Option<f64> {
		let (top, bottom) = (feet_y + RISE, feet_y + RISE_FREE);
		let hit = self.cast([x, top, z], [0.0, bottom - top, 0.0])?;
		if !hit[1].is_finite() || hit[1] > top + 0.01 || hit[1] < bottom - 0.01 {
			return None;
		}
		let in_body = |b: &V3, y: f64| (x - b[0]).hypot(z - b[2]) < BODY_RADIUS && y > b[1] + 0.3 && y < b[1] + 2.0;
		if self.bodies.iter().skip(1).any(|b| in_body(b, hit[1])) {
			return None;
		}
		match self.cast([x, hit[1] + 0.02, z], [0.0, HEADROOM, 0.0]) {
			Some(over) if !self.bodies.iter().any(|b| in_body(b, over[1])) => None,
			_ => Some(hit[1]),
		}
	}

	/// First hit (Minecraft coords) on the segment from `from` along `delta` (Minecraft axes).
	fn cast(&mut self, from: V3, delta: V3) -> Option<V3> {
		self.count += 1;
		let o = self.space.mc_to_havok(from);
		let e = self.space.mc_to_havok([from[0] + delta[0], from[1] + delta[1], from[2] + delta[2]]);
		let hit = self.world.cast_ray(
			self.filter,
			&HavokPosition::from_xyz(o[0], o[1], o[2]),
			PositionDelta(e[0] - o[0], e[1] - o[1], e[2] - o[2]),
			self.player,
		)?;
		Some(self.space.havok_to_mc([hit.0, hit.1, hit.2]))
	}
}

fn sample_xz(job: &Job, i: usize, j: usize) -> (f64, f64) {
	((job.rx * REGION) as f64 + i as f64 * job.step, (job.rz * REGION) as f64 + j as f64 * job.step)
}

/// Advances a column job within the frame budget. True when it is finished.
fn run_job(job: &mut Job, rays: &mut Rays, started: Instant, budget: Duration) -> bool {
	while job.next_sample < job.n * job.n {
		if started.elapsed() >= budget {
			return false;
		}
		let k = job.next_sample;
		let (x, z) = sample_xz(job, k % job.n, k / job.n);
		if rays.bodies.iter().any(|b| (x - b[0]).hypot(z - b[2]) < BODY_RADIUS) {
			// Every filter sees characters' capsules: don't scan through one (it would read as a
			// pillar, or eat the floor under it). The nearest sample outside it stands in.
			job.in_body[k] = true;
		} else {
			let (solids, hits) = sample_spans(rays, x, z, job.base_y);
			let surfaces = hits.into_iter().filter(|y| *y <= job.base_y + UP_REACH).collect();
			job.samples[k] = Sample { solids, surfaces };
		}
		job.next_sample += 1;
	}
	if job.walls.is_none() {
		fill_body_samples(job);
		// Far columns are floors only: no wall proposals to confirm.
		job.walls = Some(if job.far { Vec::new() } else { proposed_walls(job) });
	}
	// Horizontal confirmation shares the existing frame budget and resumes next frame.
	// Column building/publishing never starts until this phase is complete.
	while job.next_wall < job.walls.as_ref().unwrap().len() {
		if started.elapsed() >= budget { return false; }
		let wall = &job.walls.as_ref().unwrap()[job.next_wall];
		let lo = wall.lo + job.next_band as f64 * WALL_BAND;
		let hi = (lo + WALL_BAND).min(wall.hi);
		let faces = wall_faces(rays, wall, lo, hi);
		if !faces.is_empty() {
			job.confirmed_bands += 1;
			let joins = job.wall_run.len() == faces.len() && job.wall_run.iter().zip(&faces).all(|(last, face)|
				(last.hi - lo).abs() < 0.001 && edge_distance(last.p0, face.p0) <= WALL_JOIN
				&& edge_distance(last.p1, face.p1) <= WALL_JOIN);
			if joins { for face in &mut job.wall_run { face.hi = hi; } }
			else {
				flush_wall(job);
				job.wall_run = faces;
			}
		} else {
			job.rejected_bands += 1;
			flush_wall(job);
		}
		job.next_band += 1;
		if hi >= job.walls.as_ref().unwrap()[job.next_wall].hi {
			flush_wall(job);
			job.next_wall += 1;
			job.next_band = 0;
		}
	}
	true
}

/// Short, two-sided native ray across the proposed face, rather than a guessed solid volume.
/// Reject character capsules just as the vertical scan does. No long ray can promote a distant
/// wall to the open space of this half-block edge.
fn wall_hit(rays: &mut Rays, mid: (f64, f64), along_x: bool, y: f64) -> Option<(f64, f64)> {
	let axis = if along_x { 2 } else { 0 };
	let half = STEP / 2.0 + PAST_HIT;
	for sign in [-1.0, 1.0] {
		let mut from = [mid.0, y, mid.1];
		from[axis] -= sign * half;
		let mut delta = [0.0; 3];
		delta[axis] = sign * half * 2.0;
		let Some(hit) = rays.cast(from, delta) else { continue; };
		if !hit.iter().all(|v| v.is_finite()) || (hit[axis] - from[axis]).abs() > half * 2.0 + PAST_HIT
			|| (hit[1] - y).abs() > PAST_HIT { continue; }
		if rays.bodies.iter().any(|b| (hit[0] - b[0]).hypot(hit[2] - b[2]) < BODY_RADIUS
			&& hit[1] > b[1] + 0.25 && hit[1] < b[1] + 3.0) { continue; }
		return Some(if along_x { (mid.0, hit[2]) } else { (hit[0], mid.1) });
	}
	None
}

fn flush_wall(job: &mut Job) {
	let v = |xz: (f64, f64), y: f64| [xz.0 as f32, y as f32, xz.1 as f32];
	for face in job.wall_run.drain(..) {
		job.wall_tris.push(tri(v(face.p0, face.lo), v(face.p1, face.lo), v(face.p1, face.hi)));
		job.wall_tris.push(tri(v(face.p0, face.lo), v(face.p1, face.hi), v(face.p0, face.hi)));
	}
}

fn edge_distance(a: (f64, f64), b: (f64, f64)) -> f64 { (a.0 - b.0).hypot(a.1 - b.1) }

/// Fit the wall within this sampling cell. Near door-frame ends, shorten a half segment until
/// an endpoint is actually confirmed; never extend the centre hit into untested empty space.
/// Curved corners keep the centre as a vertex instead of bridging across the bend.
fn wall_faces(rays: &mut Rays, wall: &WallProbe, lo: f64, hi: f64) -> Vec<WallFace> {
	let y = (lo + hi) / 2.0;
	let Some(centre) = wall_hit(rays, wall.mid, wall.along_x, y) else { return Vec::new(); };
	let mut endpoint = |sign: f64| {
		for scale in [1.0, 0.5, 0.25] {
			let mut mid = wall.mid;
			if wall.along_x { mid.0 += sign * STEP / 2.0 * scale; }
			else { mid.1 += sign * STEP / 2.0 * scale; }
			if let Some(hit) = wall_hit(rays, mid, wall.along_x, y) { return Some(hit); }
		}
		None
	};
	let left = endpoint(-1.0);
	let right = endpoint(1.0);
	if let (Some(p0), Some(p1)) = (left, right) {
		let (dx, dz) = (p1.0 - p0.0, p1.1 - p0.1);
		let length = dx.hypot(dz);
		let bend = ((centre.0 - p0.0) * dz - (centre.1 - p0.1) * dx).abs() / length.max(1e-6);
		if bend <= 0.02 { return vec![WallFace { p0, p1, lo, hi }]; }
	}
	let mut faces = Vec::new();
	if let Some(p0) = left { faces.push(WallFace { p0, p1: centre, lo, hi }); }
	if let Some(p1) = right { faces.push(WallFace { p0: centre, p1, lo, hi }); }
	faces
}

/// Gives every sample skipped for being inside the player's body the spans of the nearest one
/// that was scanned.
fn fill_body_samples(job: &mut Job) {
	let skipped: Vec<usize> = (0..job.samples.len()).filter(|&k| job.in_body[k]).collect();
	for k in skipped {
		let (i, j) = ((k % job.n) as i32, (k / job.n) as i32);
		let nearest = (0..job.samples.len())
			.filter(|&n| !job.in_body[n])
			.min_by_key(|&n| {
				let (a, b) = ((n % job.n) as i32 - i, (n / job.n) as i32 - j);
				a * a + b * b
			});
		if let Some(n) = nearest {
			job.samples[k] = job.samples[n].clone();
		}
	}
}

/// The solid spans at (x, z) that reach into the scan window around `base_y`, and the raw
/// crossings they came from.
fn sample_spans(rays: &mut Rays, x: f64, z: f64, base_y: f64) -> (Vec<(f64, f64)>, Vec<f64>) {
	let sky = base_y + SKY;
	let bottom = base_y - DOWN_REACH;
	let mut hits = Vec::new();
	let mut y = sky;
	while hits.len() < MAX_CROSSINGS && y > bottom {
		let Some(h) = rays.cast([x, y, z], [0.0, bottom - y, 0.0]) else { break };
		if !h[1].is_finite() || h[1] > y + 0.01 || h[1] < bottom - 0.01 { break }
		hits.push(h[1]);
		y = (h[1] - PAST_HIT).min(y - PAST_HIT);
	}
	let window_top = base_y + UP_REACH;
	let solids = spans(&hits, false, sky).into_iter().filter(|(lo, _)| *lo < window_top).collect();
	(solids, hits)
}

/// Downward crossings to solid spans (bottom, top), highest first.
fn spans(hits: &[f64], starts_inside: bool, top: f64) -> Vec<(f64, f64)> {
	let mut solids: Vec<(f64, f64)> = Vec::new();
	let mut inside = starts_inside;
	let mut entered = top;
	for &h in hits {
		if inside {
			solids.push((h, entered));
		} else {
			entered = h;
		}
		inside = !inside;
	}
	if inside {
		solids.push((f64::NEG_INFINITY, entered));
	}
	// Merge slivers of air, but retain thin solids: a thin terrain mesh is still collision.
	let mut merged: Vec<(f64, f64)> = Vec::new();
	for (lo, hi) in solids {
		if let Some(last) = merged.last_mut() {
			if last.0 - hi < MIN_SPAN {
				last.0 = lo;
				continue;
			}
		}
		if hi > lo {
			merged.push((lo, hi));
		}
	}
	merged
}

fn tri(a: [f32; 3], b: [f32; 3], c: [f32; 3]) -> ColTri {
	ColTri { v: [a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]], flags: 0 }
}

/// The value nearest `to` within MAX_JOIN, if any.
fn nearest(values: impl Iterator<Item = f64>, to: f64) -> Option<f64> {
	values.filter(|v| (v - to).abs() <= MAX_JOIN).min_by(|a, b| (a - to).abs().total_cmp(&(b - to).abs()))
}

/// Smooth surfaces through a quad of samples: each top (or underside) is joined with the nearest
/// one at the other corners. Three corners make one triangle, four make two.
fn surfaces(tris: &mut Vec<ColTri>, quad: [&Sample; 4], corners: [(f64, f64); 4], pick: fn(&Sample) -> Vec<f64>) {
	let lists = quad.map(pick);
	let mut done: HashSet<[i64; 4]> = HashSet::new();
	for list in &lists {
		for &h in list {
			let chosen: [Option<f64>; 4] = std::array::from_fn(|c| nearest(lists[c].iter().copied(), h));
			let key = chosen.map(|c| c.map_or(i64::MIN, |v| (v * 1000.0).round() as i64));
			if !done.insert(key) {
				continue;
			}
			let p = |c: usize| chosen[c].map(|y| [corners[c].0 as f32, y as f32, corners[c].1 as f32]);
			// Corners: 0 (x0,z0), 1 (x1,z0), 2 (x0,z1), 3 (x1,z1).
			match (p(0), p(1), p(2), p(3)) {
				(Some(a), Some(b), Some(c), Some(d)) => {
					tris.push(tri(a, c, d));
					tris.push(tri(a, d, b));
				}
				(Some(a), Some(b), Some(c), None) => tris.push(tri(a, c, b)),
				(Some(a), Some(b), None, Some(d)) => tris.push(tri(a, d, b)),
				(Some(a), None, Some(c), Some(d)) => tris.push(tri(a, c, d)),
				(None, Some(b), Some(c), Some(d)) => tris.push(tri(b, c, d)),
				_ => {}
			}
		}
	}
}

/// Span differences propose a wall; actual native horizontal hits decide which parts exist.
/// Clip proposals to the scan window, including tall structures crossed high above the player.
fn wall_candidates(probes: &mut Vec<WallProbe>, a: &Sample, b: &Sample, mid: (f64, f64), along_x: bool, floor: f64, ceiling: f64) {
	for &(lo, hi) in &a.solids {
		let lo = lo.max(floor);
		let hi = hi.min(ceiling);
		if hi <= lo {
			continue;
		}
		// The part of a's span that b has no solid beside.
		let mut open = vec![(lo, hi)];
		for &(blo, bhi) in &b.solids {
			open = open
				.into_iter()
				.flat_map(|(l, u)| {
					let mut out = Vec::new();
					if blo > l {
						out.push((l, u.min(blo)));
					}
					if bhi < u {
						out.push((l.max(bhi), u));
					}
					out.into_iter().filter(|(l, u)| u - l > MIN_SPAN)
				})
				.collect();
		}
		for (l, u) in open {
			// A step up from b's top to a's top that the floors already join smoothly.
			if u == hi && nearest(b.tops(), hi).is_some_and(|bt| l >= bt - MIN_SPAN) {
				continue;
			}
			if u - l > MIN_SPAN { probes.push(WallProbe { mid, along_x, lo: l, hi: u }); }
		}
	}
}

fn proposed_walls(job: &Job) -> Vec<WallProbe> {
	let at = |i: usize, j: usize| &job.samples[j * SAMPLES + i];
	let floor = job.base_y - DOWN_REACH;
	let ceiling = job.base_y + UP_REACH;
	let mut probes = Vec::new();
	for j in 0..SAMPLES {
		for i in 0..SAMPLES {
			let (x, z) = sample_xz(job, i, j);
			if i + 1 < SAMPLES {
				let mid = (x + STEP / 2.0, z);
				wall_candidates(&mut probes, at(i, j), at(i + 1, j), mid, false, floor, ceiling);
				wall_candidates(&mut probes, at(i + 1, j), at(i, j), mid, false, floor, ceiling);
			}
			if j + 1 < SAMPLES {
				let mid = (x, z + STEP / 2.0);
				wall_candidates(&mut probes, at(i, j), at(i, j + 1), mid, true, floor, ceiling);
				wall_candidates(&mut probes, at(i, j + 1), at(i, j), mid, true, floor, ceiling);
			}
		}
	}

	probes
}

/// Floor, ceiling and wall triangles for a finished column, and its voxel skin.
fn build(job: &Job) -> (Vec<ColTri>, Vec<ColBlock>) {
	let (n, step) = (job.n, job.step);
	let at = |i: usize, j: usize| &job.samples[j * n + i];
	let floor = job.base_y - DOWN_REACH;
	let mut tris = job.wall_tris.clone();
	for j in 0..n - 1 {
		for i in 0..n - 1 {
			let (x0, z0) = sample_xz(job, i, j);
			let (x1, z1) = (x0 + step, z0 + step);
			let quad = [at(i, j), at(i + 1, j), at(i, j + 1), at(i + 1, j + 1)];
			let corners = [(x0, z0), (x1, z0), (x0, z1), (x1, z1)];
			// Pairing crossings can misclassify thin/overlapping meshes. The actual hit planes
			// remain authoritative for floor and ceiling collision even when no span survives.
			surfaces(&mut tris, quad, corners, |s| s.surfaces.clone());
		}
	}

	// Skin: under every top, one block deep (or down to the span's bottom), per 1/8-block voxel.
	let mut blocks: HashMap<(i32, i32, i32), [u64; 8]> = HashMap::new();
	let nearest_sample = |x: f64, z: f64| {
		let fi = ((x - (job.rx * REGION) as f64) / step).round().clamp(0.0, (n - 1) as f64) as usize;
		let fj = ((z - (job.rz * REGION) as f64) / step).round().clamp(0.0, (n - 1) as f64) as usize;
		at(fi, fj)
	};
	for bz in 0..REGION {
		for bx in 0..REGION {
			for vz in 0..8 {
				for vx in 0..8 {
					let x = (job.rx * REGION + bx) as f64 + (vx as f64 + 0.5) / 8.0;
					let z = (job.rz * REGION + bz) as f64 + (vz as f64 + 0.5) / 8.0;
					for &(lo, hi) in &nearest_sample(x, z).solids {
						let top = (hi * 8.0).round() as i64; // in eighths
						let bottom = (lo.max(hi - SKIN).max(floor) * 8.0).round() as i64;
						for e in bottom..top {
							let by = e.div_euclid(8) as i32;
							let vy = e.rem_euclid(8) as usize;
							let key = (job.rx * REGION + bx, by, job.rz * REGION + bz);
							blocks.entry(key).or_default()[vy] |= 1u64 << (vz * 8 + vx);
						}
					}
				}
			}
		}
	}
	let blocks = blocks.into_iter().map(|((x, y, z), bits)| ColBlock { pos: [x, y, z], pad: 0, bits }).collect();
	(tris, blocks)
}

/// Columns whose triangles the debug view should draw.
pub fn near_columns(feet: V3, radius: i32) -> HashSet<(i32, i32)> {
	let prx = (feet[0].floor() as i32).div_euclid(REGION);
	let prz = (feet[2].floor() as i32).div_euclid(REGION);
	let mut out = HashSet::new();
	for rx in prx - radius..=prx + radius {
		for rz in prz - radius..=prz + radius {
			out.insert((rx, rz));
		}
	}
	out
}

//! Elden Ring interactions while Minecraft moves the player: doors, levers, item pickups,
//! graces and message/popup confirmation all use ER's Event Action key, which the input block
//! otherwise withholds. R (unbound in vanilla Minecraft) taps it; `dinput` injects the key.
//!
//! ER only offers an interaction the body faces, so the native body turns with Minecraft's look
//! while Minecraft drives. An interaction can start a native animation that aligns or moves the
//! body (doors, levers, fog walls, pickups); Minecraft's per-frame position requests would fight
//! it, so ER owns the body until that animation finishes and Minecraft follows it meanwhile.

use std::time::{Duration, Instant};

use eldenring::cs::PlayerIns;
use eldenring::rotation::Quaternion;

use crate::log;

/// No new animation this soon after the tap: nothing was interacted with (or a popup closed).
const ACTION_START: Duration = Duration::from_millis(600);
/// A stuck or unrecognised animation never keeps Minecraft from moving for longer than this.
const ACTION_LIMIT: Duration = Duration::from_secs(8);
/// Running this fast, ER's body faces its own movement: that verifies the forward axis.
const FORWARD_LEARN_SPEED: f64 = 3.0;

#[derive(Clone, Copy, PartialEq)]
struct Anim { id: i32, time: f32, length: f32 }

fn current_anim(player: &PlayerIns) -> Anim {
	let t = &player.chr_ins.modules.time_act;
	let a = &t.anim_queue[(t.read_idx % 10) as usize];
	Anim { id: a.anim_id, time: a.play_time, length: a.anim_length }
}

struct Action { since: Instant, baseline: i32, playing: Option<i32> }

pub struct Interact {
	action: Option<Action>,
	/// +1: the body's local -Z is forward (the SDK convention); -1 if ER running disagrees.
	forward: f32,
	forward_dot: f64,
	forward_samples: u32,
	last_havok: Option<([f32; 3], Instant)>,
	written_yaw: Option<f32>,
	overridden: u32,
	next_report: Instant,
}

impl Interact {
	pub fn new() -> Self {
		Self { action: None, forward: 1.0, forward_dot: 0.0, forward_samples: 0, last_havok: None,
			written_yaw: None, overridden: 0, next_report: Instant::now() }
	}

	/// ER owns the body: an interaction animation is playing.
	pub fn holds_body(&self) -> bool {
		self.action.as_ref().is_some_and(|a| a.playing.is_some())
	}

	pub fn cancel(&mut self) {
		self.action = None;
		self.written_yaw = None;
	}

	/// R was pressed while Minecraft owns the controls.
	pub fn begin(&mut self, player: &PlayerIns) {
		crate::dinput::tap_event_action();
		if self.action.is_none() {
			let a = current_anim(player);
			log::line(&format!("interact: Event Action tapped (animation {} at {:.2}/{:.2} s)", a.id, a.time, a.length));
			self.action = Some(Action { since: Instant::now(), baseline: a.id, playing: None });
		}
	}

	/// Advance the action window from this frame's animation.
	pub fn update(&mut self, player: &PlayerIns) {
		let Some(action) = self.action.as_mut() else { return };
		let a = current_anim(player);
		let elapsed = action.since.elapsed();
		match action.playing {
			None if a.id != action.baseline => {
				action.playing = Some(a.id);
				log::line(&format!("interact: ER plays animation {} ({:.2} s); Minecraft follows the body until it ends", a.id, a.length));
			}
			None if elapsed >= ACTION_START => {
				self.action = None; // a closed popup, or nothing in reach
			}
			None => {}
			Some(id) => {
				let finished = a.id == action.baseline || (a.id == id && a.length > 0.0 && a.time >= a.length);
				if finished || elapsed >= ACTION_LIMIT {
					log::line(&format!("interact: animation {} ended after {:.1} s{}; Minecraft drives again",
						id, elapsed.as_secs_f32(), if finished { "" } else { " (time limit)" }));
					self.action = None;
				} else if a.id != id {
					action.playing = Some(a.id); // a chained part, e.g. stepping through after a push
					log::line(&format!("interact: ER continues with animation {} ({:.2} s)", a.id, a.length));
				}
			}
		}
	}

	/// While ER animates the body itself, check which local axis is forward against running.
	pub fn learn_forward(&mut self, player: &PlayerIns) {
		self.written_yaw = None;
		let physics = &player.chr_ins.modules.physics;
		let h = [physics.position.0, physics.position.1, physics.position.2];
		let now = Instant::now();
		if let Some((old, at)) = self.last_havok.replace((h, now)) {
			let dt = now.duration_since(at).as_secs_f64();
			let (dx, dz) = ((h[0] - old[0]) as f64, (h[2] - old[2]) as f64);
			let step = dx.hypot(dz);
			if dt > 0.0 && dt <= 0.1 && step / dt >= FORWARD_LEARN_SPEED && step / dt < 20.0 {
				let f = glam::Quat::from(physics.orientation).mul_vec3(glam::vec3(0.0, 0.0, -1.0));
				let len = (f.x as f64).hypot(f.z as f64);
				if len > 0.5 {
					let dot = (f.x as f64 * dx + f.z as f64 * dz) / (len * step);
					self.forward_samples += 1;
					self.forward_dot += (dot - self.forward_dot) / f64::from(self.forward_samples.min(200));
					let sign = if self.forward_dot < -0.5 { -1.0 } else { 1.0 };
					if self.forward_samples == 30 || (self.forward_samples > 30 && sign != self.forward) {
						self.forward = sign;
						log::line(&format!("interact: body forward axis {} (running agreement {:+.2} over {} samples)",
							if sign > 0.0 { "-Z" } else { "+Z" }, self.forward_dot, self.forward_samples));
					}
				}
			}
		}
	}

	/// Turn the body to Minecraft's look yaw (degrees) while Minecraft drives it.
	pub fn face(&mut self, player: &mut PlayerIns, yaw: f32) {
		self.last_havok = None;
		let physics = player.chr_ins.modules.physics.as_mut();
		// A changed orientation since our last write means the game turned the body itself.
		if let Some(written) = self.written_yaw {
			let f = glam::Quat::from(physics.orientation).mul_vec3(glam::vec3(0.0, 0.0, -self.forward));
			let now = crate::world::yaw_pitch(crate::world::dir_to_mc([f.x, f.y, f.z])).0;
			if ((now - written + 540.0).rem_euclid(360.0) - 180.0).abs() > 20.0 { self.overridden += 1; }
		}
		// Local -Z (times `forward`) rotated by theta about Y points along MC yaw theta.
		let theta = yaw.to_radians() + if self.forward < 0.0 { std::f32::consts::PI } else { 0.0 };
		let q = glam::Quat::from_rotation_y(theta);
		let q = Quaternion(q.x, q.y, q.z, q.w);
		physics.orientation = q;
		physics.interpolated_orientation = q;
		self.written_yaw = Some(yaw);
		if Instant::now() >= self.next_report {
			self.next_report = Instant::now() + Duration::from_secs(10);
			if self.overridden > 0 {
				log::line(&format!("interact: the game turned the body away from Minecraft's look {} times in 10 s", self.overridden));
			}
			self.overridden = 0;
		}
	}
}

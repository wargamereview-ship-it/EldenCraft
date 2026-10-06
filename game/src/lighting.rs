//! The light Minecraft's blocks are drawn with: Elden Ring's time of day outdoors (a sun that
//! crosses the sky, warm at dawn and dusk, a dim blue moon at night) and a dim, even ambient
//! indoors and underground. The game's own region and weather tints are not read, so this follows
//! the clock and the kind of place, not the exact look of each area.

/// Directional light plus ambient, in Minecraft's axes (x east, y up, z south).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Light {
	/// Unit vector toward the light.
	pub dir: [f32; 3],
	/// Colour of the directional light (sun, or moon at night); black indoors.
	pub direct: [f32; 3],
	/// Colour of the light that reaches every face.
	pub ambient: [f32; 3],
}

impl Default for Light {
	fn default() -> Self {
		light(12.0, true)
	}
}

fn lerp(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
	[0, 1, 2].map(|i| a[i] + (b[i] - a[i]) * t)
}

fn smooth(lo: f32, hi: f32, x: f32) -> f32 {
	let t = ((x - lo) / (hi - lo)).clamp(0.0, 1.0);
	t * t * (3.0 - 2.0 * t)
}

fn unit(v: [f32; 3]) -> [f32; 3] {
	let n = (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).sqrt().max(1e-6);
	v.map(|c| c / n)
}

/// Light at `hour` (0..24, local to the world's clock), outdoors or not.
pub fn light(hour: f32, outdoors: bool) -> Light {
	if !outdoors {
		return Light { dir: [0.0, 1.0, 0.0], direct: [0.0; 3], ambient: [0.34, 0.34, 0.38] };
	}
	// The sun rises at 6 and sets at 18, a half circle from east to west, tilted a little south.
	let angle = (hour.rem_euclid(24.0) - 6.0) / 12.0 * std::f32::consts::PI;
	let elevation = angle.sin();
	let day = smooth(-0.05, 0.35, elevation);
	// Low sun is warm, high sun is white.
	let warmth = 1.0 - smooth(0.0, 0.5, elevation.max(0.0));
	// Each fades in from nothing at the horizon, so the light does not pop when it changes body.
	let sun = lerp([1.0, 0.96, 0.88], [1.0, 0.55, 0.28], warmth).map(|c| c * day * 0.85 * smooth(0.0, 0.15, elevation));
	let moon = [0.25, 0.32, 0.55].map(|c| c * (1.0 - day) * 0.35 * smooth(0.0, -0.25, elevation));
	let toward = [angle.cos(), elevation, 0.35];
	let (dir, direct) = if elevation >= 0.0 { (unit(toward), sun) } else { (unit([-toward[0], -toward[1], -toward[2]]), moon) };
	let dusk = [0.08, 0.04, 0.0].map(|c| c * warmth * day);
	let ambient = lerp([0.10, 0.13, 0.22], [0.50, 0.57, 0.68], day);
	Light { dir, direct, ambient: [0, 1, 2].map(|i| ambient[i] + dusk[i]) }
}

impl Light {
	/// Moves a fraction `k` of the way to `target` (entering a cave should not snap).
	pub fn approach(&mut self, target: Light, k: f32) {
		let mix = |a: [f32; 3], b: [f32; 3]| lerp(a, b, k);
		self.dir = unit(mix(self.dir, target.dir));
		self.direct = mix(self.direct, target.direct);
		self.ambient = mix(self.ambient, target.ambient);
	}
}

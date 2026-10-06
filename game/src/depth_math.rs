//! How a game's depth buffer value relates to distance, for the encodings games use. Elden
//! Ring's is not documented, so the renderer measures it: it reads one depth value where the
//! distance is known (a native ray through the middle of the picture) and sees which encoding fits.

pub const STANDARD: u32 = 1;
pub const REVERSED: u32 = 2;
pub const REVERSED_INFINITE: u32 = 3;

/// Distance along the view axis for a stored depth `d`, camera near `n` and far `f`.
pub fn distance(mode: u32, d: f32, n: f32, f: f32) -> f32 {
	match mode {
		STANDARD => f * n / (f - d * (f - n)),
		REVERSED => f * n / (n + d * (f - n)),
		_ => n / d.max(1e-7),
	}
}

/// The stored depth for a point `dist` away (the inverse of `distance`; tests and diagnostics).
#[allow(dead_code)]
pub fn encode(mode: u32, dist: f32, n: f32, f: f32) -> f32 {
	match mode {
		STANDARD => f * (dist - n) / (dist * (f - n)),
		REVERSED => n * (f - dist) / (dist * (f - n)),
		_ => n / dist,
	}
}

/// Which encoding turns the stored value `sample` into `truth` (the distance known by other
/// means), within 3%. None if no encoding does, or the inputs make no sense.
pub fn calibrate(sample: f32, truth: f32, n: f32, f: f32) -> Option<u32> {
	if !(sample.is_finite() && truth.is_finite() && truth > n * 1.5 && n > 0.0 && f > n * 2.0 && (0.0..=1.0).contains(&sample)) {
		return None;
	}
	[STANDARD, REVERSED, REVERSED_INFINITE]
		.iter()
		.copied()
		.map(|mode| (mode, (distance(mode, sample, n, f) - truth).abs() / truth))
		.filter(|(_, error)| *error <= 0.03)
		.min_by(|a, b| a.1.total_cmp(&b.1))
		.map(|(mode, _)| mode)
}

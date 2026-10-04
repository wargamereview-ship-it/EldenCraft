//! hudhook's render loop. Drawing is done by `gpu` (hudhook calls it on every present, before
//! this loop); what is left here keeps hudhook's DirectX 12 hooks alive and reports in.

use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{Duration, Instant};

use hudhook::imgui::{Context, Ui};
use hudhook::{ImguiRenderLoop, RenderContext};

use crate::log;

/// The game's back buffer size, for Minecraft to size its GUI to.
static VIEWPORT: AtomicU32 = AtomicU32::new(0);

pub fn set_viewport(width: u32, height: u32) {
	VIEWPORT.store((width << 16) | (height & 0xFFFF), Ordering::Relaxed);
}

/// (width, height) of the game's picture, once known.
pub fn viewport() -> Option<(u32, u32)> {
	let v = VIEWPORT.load(Ordering::Relaxed);
	(v != 0).then(|| (v >> 16, v & 0xFFFF))
}

pub struct Hud {
	next_beat: Instant,
}

impl Hud {
	pub fn new() -> Self {
		Self { next_beat: Instant::now() }
	}
}

impl ImguiRenderLoop for Hud {
	fn initialize<'a>(&'a mut self, ctx: &mut Context, _render_context: &'a mut dyn RenderContext) {
		ctx.set_ini_filename(None);
		ctx.io_mut().mouse_draw_cursor = false;
		log::line("hud: DirectX 12 hook running");
	}

	fn before_render<'a>(&'a mut self, _ctx: &mut Context, _render_context: &'a mut dyn RenderContext) {
		if Instant::now() >= self.next_beat {
			self.next_beat = Instant::now() + Duration::from_secs(10);
			log::line(&format!("hud: alive, viewport {:?}", viewport()));
		}
	}

	fn render(&mut self, _ui: &mut Ui) {}
}

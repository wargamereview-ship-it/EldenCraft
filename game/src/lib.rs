//! EldenCraft's Elden Ring side, loaded into the game by me3 (or ModEngine2).
//!
//! Minecraft drives by default and F8 no longer hands movement back to Elden Ring. Each frame publishes the
//! current body position, exports collision, and queues Minecraft's destination through the
//! game's character controller while Minecraft drives.

mod aggro;
mod ai_sound;
mod camera;
mod combat;
mod loot;
mod loot_catalog;
mod resources;
mod grace_edges;
mod depth;
mod depth_math;
mod lighting;
mod dinput;
mod hud;
mod input;
mod interact;
mod keys;
mod launcher;
mod link;
mod log;
mod movement;
mod native_damage;
mod native_hits;
mod player_status;
#[allow(dead_code)] // mirrors the whole protocol, not only what is used yet
mod proto;
mod scene;
mod gpu;
mod render;
mod scan;
mod world;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::time::{Duration, Instant};

use eldenring::cs::{CSCamera, CSCamExt, CSTaskGroupIndex, CSTaskImp, FieldArea, GameDataMan, HudType, PlayerIns, WorldChrMan};
use eldenring::fd4::FD4TaskData;
use fromsoftware_shared::{FromStatic, SharedTaskImpExt};

use input::Input;
use keys::Keys;
use link::{Link, Publish};
use render::Blocks;
use scan::Scanner;
use world::Space;

/// Mirror mode: how far (blocks) Elden Ring's player may get from where Minecraft was last sent
/// before Minecraft is teleported again.
const RESYNC_BLOCKS: f64 = 0.5;
/// Mirror mode: Minecraft's player this far from Elden Ring's (fell through something) is put back.
const MIRROR_DRIFT: f64 = 2.0;
/// A jump in Elden Ring's observed position, away from our queued destination, is game travel.
const WARP_BLOCKS: f64 = 3.0;
/// Sustained separation means the controller did not accept movement, rather than a game warp.
const MOVEMENT_LAG_METERS: f64 = 1.0;
const MOVEMENT_TIMEOUT: Duration = Duration::from_secs(1);
/// Brief seqlock collisions retain the last camera/input state; a stopped publisher expires.
const MC_SNAPSHOT_TTL: Duration = Duration::from_millis(250);
/// Refresh a delayed handoff once, then return control rather than leave a frozen MC view.
const HANDOFF_REFRESH: Duration = Duration::from_secs(2);
const HANDOFF_TIMEOUT: Duration = Duration::from_secs(8);
/// Repeated failure at the same recovery point hands control back instead of teleporting forever.
const RECOVERY_RETRY_WINDOW: Duration = Duration::from_secs(10);
const GROUND_CLEARANCE: f64 = 0.1;
/// Repeated small slope corrections are not evidence that the player fell out of the world.
const DEEP_RECOVERY_METERS: f64 = 0.75;
/// The tile-local position the mapping is learned from is exact once settled but can lag or be
/// stale for a while (loading, falling, our own writes). A mapping is only taken once this many
/// frames in a row agree within ANCHOR_AGREE metres...
const ANCHOR_FRAMES: u32 = 20;
const ANCHOR_AGREE: f64 = 0.25;
/// ...and, once taken, is only replaced by one at least this far from it that holds as steadily
/// (the game re-basing its physics space). Anything less is lag and is ignored.
const REANCHOR_METERS: f64 = 20.0;
/// A re-centre moves the havok position this little or less in world terms.
const REBASE_WORLD_STILL: f64 = 2.0;
/// A connected map change (a cave mouth, no loading screen) renames the map under the same
/// live body while its havok position keeps moving continuously. The held mapping still maps
/// that body exactly, so the MC view and movement continue on it while the new one settles.
/// A larger per-frame havok step is a warp or re-centre; a longer wait is a real load.
const CONNECTED_STEP: f64 = 2.0;
const CONNECTED_HOLD: Duration = Duration::from_secs(2);
/// In game this long without Minecraft connecting: Prism is assumed stuck and restarted.
const MINECRAFT_CONNECT_TIMEOUT: Duration = Duration::from_secs(60);
/// Native contact is allowed only beside a freshly measured ER floor. Minecraft blocks,
/// air and unacknowledged teleports still need the ER body suspended.
const NATIVE_CONTACT_GAP: f64 = 0.45;
const NATIVE_CONTACT_BODY_LAG: f64 = 0.5;
/// Region boundaries can chatter; refresh once settled, then after geometry has streamed.
const STREAM_REGION_SETTLE: Duration = Duration::from_millis(200);
const STREAM_COLLISION_REFRESH: Duration = Duration::from_secs(2);
/// The camera's aim point is searched this far along its view.
const AIM_REACH: f64 = 40.0;

#[unsafe(no_mangle)]
/// # Safety
///
/// Called by the Windows loader. Do not call this yourself.
pub unsafe extern "C" fn DllMain(hmodule: usize, reason: u32) -> bool {
	const DLL_PROCESS_ATTACH: u32 = 1;
	if reason == DLL_PROCESS_ATTACH {
		// Everything else waits for the loader lock to be released, on our own thread.
		std::thread::spawn(move || start(hmodule));
	}
	true
}

fn start(hmodule: usize) {
	log::init(hmodule);
	std::panic::set_hook(Box::new(|info| log::line(&format!("panic: {info}"))));
	log::line(&format!("EldenCraft {} loaded", env!("CARGO_PKG_VERSION")));
	let link = match Link::create() {
		Ok(link) => link,
		Err(e) => {
			log::line(&format!("shared memory: {e}; EldenCraft is off"));
			return;
		}
	};
	log::line("shared memory ready");
	dinput::install();
	launcher::start_minecraft();
	log::line("waiting for the game's task runner");

	let Ok(cs_task) = CSTaskImp::wait_for_instance(Duration::MAX) else {
		log::line("CSTaskImp never appeared; EldenCraft is off");
		return;
	};

	let hooks = hudhook::Hudhook::builder()
		.with::<hudhook::hooks::dx12::ImguiDx12Hooks>(hud::Hud::new())
		.with_hmodule(hudhook::windows::Win32::Foundation::HINSTANCE(hmodule as _))
		.build();
	gpu::install(link.share());
	match hooks.apply() {
		Ok(()) => log::line("hud: DirectX 12 hook installed"),
		Err(e) => log::line(&format!("hud: DirectX 12 hook failed ({e:?}); no Minecraft HUD")),
	}

	native_hits::install();
	native_damage::install();
	aggro::install();
	ai_sound::install();
	let mut frame = Frame::new(link);
	let handle = cs_task.run_recurring(
		move |_: &FD4TaskData| {
			if catch_unwind(AssertUnwindSafe(|| frame.run())).is_err() && !frame.panicked {
				frame.panicked = true;
				log::line("frame task panicked; see above");
			}
		},
		// After the parallel character physics tasks finish: observe the body, then queue its
		// destination for the next update without racing the character's own post-physics work.
		CSTaskGroupIndex::ChrIns_PostPhysicsSafe,
	);
	// Right after the game's own camera update and before the frame is drawn: first person.
	let camera = cs_task.run_recurring(|_: &FD4TaskData| camera::apply(), CSTaskGroupIndex::DrawParamUpdate);
	// Dropping a handle cancels its task. These run for the life of the game.
	std::mem::forget(handle);
	std::mem::forget(camera);
	log::line("frame task registered");
	log::line("movement: actual MC tick feet drive the controller; collision is refreshed after loads; teleport acknowledgement is bounded");
	log::line("maps: native terrain contact enabled beside fresh floors; region transitions refresh collision; streaming/ground diagnostics enabled");
	log::line("collision: native rays confirm wall bands; thin surfaces retained; native downward destination sweep enabled");
	log::line("combat: Minecraft weapon bridge enabled (25 ER HP per MC damage); confirmed hit feedback and native AI notice; physical hit reactions pending");
}

struct ModelState {
	body: (usize, usize, usize),
	alpha: f32,
	render: bool,
}

fn player_identity(player: &PlayerIns) -> (usize, usize, usize) {
	(player as *const PlayerIns as usize,
		&*player.chr_ins.modules.physics as *const _ as usize,
		player.chr_ins.chr_ctrl.as_ref() as *const _ as usize)
}

struct Frame {
	link: Link,
	scanner: Scanner,
	blocks: Blocks,
	combat: combat::Combat,
	/// Learned ER player sounds, replayed as native AI footsteps while Minecraft drives.
	hearing: ai_sound::Hearing,
	/// ER Event Action (R) and the native animation window it can start.
	interact: interact::Interact,
	resources: resources::Resources,
	keys: Keys,
	input: Input,
	show_collision: bool,
	collision_draw: render::CollisionDraw,
	/// The light the renderer is easing toward / currently uses (Minecraft's blocks).
	light: lighting::Light,
	/// Blocks are lit from the game's own picture (F4).
	game_light: bool,
	/// Blocks hidden by the game's real scene depth instead of the scanned collision (on by default; F3 switches it off).
	depth_occlusion: bool,
	/// The game camera sits in Minecraft's eye (and the Elden Ring body is hidden).
	first_person: bool,
	/// Original transparency/render state while our camera hides this body's model.
	model_state: Option<ModelState>,
	/// Minecraft's look (yaw, pitch), turned by the mouse while Minecraft has the controls.
	look: Option<(f32, f32)>,
	/// F10: the controls stay with Elden Ring.
	er_controls: bool,
	/// Minecraft drove last frame (gravity and the game's HUD were switched for it).
	driving_was: bool,
	/// The game's HUD setting before Minecraft took over, to put back.
	saved_hud: Option<HudType>,
	menu_was: bool,
	/// Minecraft moves the player (off: Elden Ring does, and Minecraft mirrors it).
	drive: bool,
	teleport_seq: u32,
	/// Mirror mode: where Minecraft was last teleported to.
	sent: Option<[f64; 3]>,
	/// Drive mode: the last destination queued for Elden Ring's controller.
	written: Option<[f64; 3]>,
	/// Actual ER position before queueing movement, to distinguish game travel from a failed write.
	observed: Option<[f64; 3]>,
	/// Identity of the player this mapping and movement state belong to.
	player_id: (usize, usize, usize),
	/// ER can respawn into the same player/controller objects in the same map.
	saw_dead_body: bool,
	/// A movement request has not brought the body along for this long.
	movement_lag: Option<Instant>,
	/// Last complete Minecraft state and when its sequence last changed.
	mc_cache: Option<(link::McView, Instant)>,
	/// Time waiting for the current teleport, and whether its local scan was refreshed.
	handoff_wait: Option<(Instant, bool)>,
	loading_was: bool,
	/// The havok-to-world mapping in use (see `anchor`).
	space: Option<Space>,
	/// A different mapping seen this many frames in a row.
	candidate: Option<(Space, u32)>,
	/// Last frame's freshly learned mapping (to tell a re-centre from lag).
	prev_fresh: Option<Space>,
	/// A connected map change is being held on the previous mapping since then.
	connected_since: Option<Instant>,
	/// Last terrain recovery, to avoid repeatedly teleporting into a broken collision patch.
	recovery: Option<([f64; 3], Instant)>,
	/// Which way the camera matrix's forward row points (+1 or -1), learned in third person.
	cam_sign: f64,
	world_id: u32,
	/// Settled native region and a candidate, paired with the current collision world.
	stream_region: Option<(u32, u32)>,
	stream_candidate: Option<((u32, u32), Instant)>,
	stream_refresh: Option<Instant>,
	mc_was_connected: bool,
	/// When the player was first in game with Minecraft not connected (None: connected).
	waiting_for_mc: Option<Instant>,
	next_report: Instant,
	panicked: bool,
}

fn dist(a: [f64; 3], b: [f64; 3]) -> f64 {
	((a[0] - b[0]).powi(2) + (a[1] - b[1]).powi(2) + (a[2] - b[2]).powi(2)).sqrt()
}

impl Frame {
	fn new(link: Link) -> Self {
		Self {
			link,
			scanner: Scanner::new(),
			blocks: Blocks::new(),
			combat: combat::Combat::new(),
			hearing: ai_sound::Hearing::new(),
			interact: interact::Interact::new(),
			resources: resources::Resources::default(),
			keys: Keys::default(),
			input: Input::default(),
			show_collision: false,
			collision_draw: render::CollisionDraw::default(),
			light: lighting::Light::default(),
			game_light: true,
			depth_occlusion: true,
			first_person: true,
			model_state: None,
			look: None,
			er_controls: false,
			driving_was: false,
			saved_hud: None,
			menu_was: false,
			drive: true, // Minecraft drives; it is never handed back to Elden Ring except while dead
			teleport_seq: 0,
			sent: None,
			written: None,
			observed: None,
			player_id: (0, 0, 0),
			saw_dead_body: false,
			movement_lag: None,
			mc_cache: None,
			handoff_wait: None,
			loading_was: true,
			recovery: None,
			space: None,
			candidate: None,
			prev_fresh: None,
			connected_since: None,
			cam_sign: 1.0,
			world_id: 0,
			stream_region: None,
			stream_candidate: None,
			stream_refresh: None,
			mc_was_connected: false,
			waiting_for_mc: None,
			next_report: Instant::now(),
			panicked: false,
		}
	}

	fn publish_loading(&mut self, mut player: Option<&mut PlayerIns>) {
		self.loading_was = true;
		ai_sound::unwatch();
		self.stream_region = None;
		self.stream_candidate = None;
		self.stream_refresh = None;
		self.handoff_wait = None;
		self.mc_cache = None;
		self.combat.clear(&self.link, player.as_deref_mut());
		self.link.publish_ground(self.world_id, self.scanner.epoch(), None);
		// Title screen or a loading screen: Minecraft holds its player still.
		if let Some(player) = player {
			if let Some(space) = self.space {
				movement::cancel(player, &space, self.written);
			}
			if self.driving_was {
				player.chr_ins.chr_flags1c4.set_no_gravity(false);
			}
			self.set_model_hidden(player, false);
		}
		if let Some(hud) = self.saved_hud.take() {
			if let Ok(data) = unsafe { GameDataMan::instance_mut() } {
				data.game_settings.as_mut().hud_type = hud;
			} else {
				self.saved_hud = Some(hud);
			}
		}
		self.driving_was = false;
		self.look = None;
		camera::set(None);
		scene::with(|s| {
			s.view = None;
			s.avatar_at = None;
			s.hud = scene::Hud::default();
		});
		dinput::set_block(false);
		self.input.poll(&self.link, false, None, (0, 0));
		self.link.publish(&Publish {
			flags: proto::GAME_LOADING,
			world_id: self.world_id,
			collision_epoch: self.scanner.epoch(),
			pos: self.sent.unwrap_or_default(),
			yaw: 0.0,
			pitch: 0.0,
			teleport_seq: self.teleport_seq,
			viewport: hud::viewport().unwrap_or((1920, 1080)),
		});
		self.sent = None;
		self.written = None;
		self.observed = None;
		self.movement_lag = None;
		self.recovery = None;
	}

	fn set_model_hidden(&mut self, player: &mut PlayerIns, hidden: bool) {
		let body = player_identity(player);
		// Keep only identity across a missing-player loading frame. A returning live
		// body can restore its alpha; a replacement must never inherit the old alpha.
		if self.model_state.as_ref().is_some_and(|s| s.body != body) {
			self.model_state = None;
		}
		let chr = &mut player.chr_ins;
		if hidden {
			// Leave the native model and its update path enabled while hiding its pixels.
			// Render eligibility is also consulted by character-module checks in this
			// build; disabling enable_render for the whole MC session can stale those.
			self.model_state.get_or_insert(ModelState { body, alpha: chr.base_transparency, render: chr.chr_flags1c5.enable_render() });
			chr.chr_flags1c5.set_enable_render(true);
			chr.base_transparency = 0.0;
		} else if let Some(saved) = self.model_state.take() {
			if chr.base_transparency == 0.0 {
				chr.base_transparency = saved.alpha;
			}
			if chr.chr_flags1c5.enable_render() {
				chr.chr_flags1c5.set_enable_render(saved.render);
			}
		}
	}

	fn refresh_stream_collision(&mut self, space: &Space, feet: [f64; 3]) {
		let Some(region) = (unsafe { FieldArea::instance() }).ok()
			.map(|f| f.current_play_region_id).filter(|r| *r != 0 && *r != u32::MAX) else {
			self.stream_candidate = None;
			return;
		};
		let observed = (space.world_id, region);
		let now = Instant::now();
		match self.stream_region {
			None => { self.stream_region = Some(observed); }
			Some(old) if old == observed => { self.stream_candidate = None; }
			Some(old) => {
				let since = match self.stream_candidate {
					Some((candidate, since)) if candidate == observed => since,
					_ => { self.stream_candidate = Some((observed, now)); now }
				};
				if now.duration_since(since) >= STREAM_REGION_SETTLE {
					self.stream_region = Some(observed);
					self.stream_candidate = None;
					self.scanner.refresh_near(feet);
					self.stream_refresh = Some(now + STREAM_COLLISION_REFRESH);
					log::line(&format!("maps: native region {} -> {}; refreshing nearby collision now and after streaming", old.1, region));
				}
			}
		}
		if self.stream_refresh.is_some_and(|at| now >= at) {
			// Keep existing surfaces until replacements arrive, including the entry floor.
			self.scanner.refresh_near(feet);
			self.stream_refresh = None;
			log::line(&format!("maps: refreshed streamed collision for native region {region}"));
		}
	}

	fn teleport_minecraft(&mut self, to: [f64; 3]) {
		self.teleport_seq = self.teleport_seq.wrapping_add(1);
		self.sent = Some(to);
		self.written = None;
		self.movement_lag = None;
		self.handoff_wait = None;
	}

	fn restore_above_floor(&mut self, player: &mut PlayerIns, space: &Space, to: [f64; 3]) {
		// ER has already followed some of Minecraft's fall. Restore the physical body too,
		// so handing control back cannot leave it below the floor and continue the fall.
		movement::cancel(player, space, self.written);
		self.teleport_minecraft(to);
		match movement::request(player, space, to, None) {
			movement::Request::Queued => self.written = Some(to),
			movement::Request::Pending => log::line("collision: body recovery awaits the game's pending movement request"),
			movement::Request::Invalid => log::line("collision: invalid body recovery destination"),
		}
	}

	fn minecraft_state(&mut self) -> Option<link::McView> {
		// A mid-write read is normal. It must not turn off the first-person camera, release
		// held keys, or briefly re-enable ER gravity for a single frame.
		for _ in 0..3 {
			if let Some(view) = self.link.read_mc() {
				let seen = self.mc_cache.as_ref()
					.filter(|(old, _)| old.sequence == view.sequence)
					.map_or_else(Instant::now, |(_, seen)| *seen);
				self.mc_cache = Some((view, seen));
				break;
			}
		}
		self.mc_cache.as_ref().filter(|(_, seen)| seen.elapsed() <= MC_SNAPSHOT_TTL)
			.map(|(view, _)| view.clone())
	}

	fn run(&mut self) {
		self.link.heartbeat();
		self.blocks.read(&self.link);
		self.resources.frame(&self.link);

		let Ok(world) = (unsafe { WorldChrMan::instance_mut() }) else {
			self.player_id = (0, 0, 0);
			return self.publish_loading(None);
		};
		let nearby = combat::nearby(world);
		let Some(player) = world.main_player.as_mut() else {
			self.player_id = (0, 0, 0);
			return self.publish_loading(None);
		};
		let player: &mut PlayerIns = player.as_mut();
		let player_id = player_identity(player);
		if self.player_id != player_id {
			self.combat.clear(&self.link, Some(player));
			// A respawn/load may replace the character in the same map. Learn a fresh mapping
			// and start the handoff from this body's position, never the previous body's target.
			self.player_id = player_id;
			self.space = None;
			self.candidate = None;
			self.prev_fresh = None;
			self.sent = None;
			self.written = None;
			self.observed = None;
			self.movement_lag = None;
			self.recovery = None;
			self.set_model_hidden(player, false);
		}
		if self.saw_dead_body && player.chr_ins.modules.data.hp > 0 && !player.chr_ins.chr_flags1c5.death_flag() {
			self.saw_dead_body = false;
			self.space = None;
			self.candidate = None;
			self.prev_fresh = None;
			self.drive = true;
			log::line("movement: native respawn detected; relearning the mapping even when this body/map was reused");
			return self.publish_loading(Some(player));
		}
		let Some(fresh) = Space::of(player) else {
			self.player_id = (0, 0, 0);
			self.publish_loading(Some(player));
			self.space = None;
			self.candidate = None;
			return;
		};
		let previous_space = self.space;
		let alive = player.chr_ins.modules.data.hp > 0 && !player.chr_ins.chr_flags1c5.death_flag();
		let Some(space) = self.anchor(fresh, alive) else {
			// anchor may have just accepted a different offset. Cancel our queued target
			// with the mapping that encoded it, before publish_loading uses the new one.
			if let Some(old) = previous_space {
				movement::cancel(player, &old, self.written);
			}
			// Not steady yet (a load, a map change): Minecraft holds still until it is.
			return self.publish_loading(Some(player));
		};
		if self.loading_was {
			self.loading_was = false;
			self.scanner.reset_after_load();
			self.resources.arrived();
			self.sent = None;
			self.observed = None;
			log::line("movement: load settled; refreshing collision and Minecraft handoff for this body");
		}

		if self.keys.pressed(keys::VK_F8) && !self.drive {
			// Only reachable if a death left Elden Ring in charge; F8 gives control back to Minecraft.
			self.drive = true;
			self.interact.cancel();
			let handback = if !self.drive && player.chr_ins.modules.data.hp > 0 {
				let p = player.chr_ins.modules.physics.position;
				let at = space.havok_to_mc([p.0, p.1, p.2]);
				self.scanner.buried_floor(at).map(|floor| [at[0], floor + GROUND_CLEARANCE, at[2]])
			} else { None };
			if !self.drive {
				movement::cancel(player, &space, self.written);
			}
			self.written = None;
			self.movement_lag = None;
			self.recovery = None;
			// Minecraft takes over from exactly where Elden Ring's player stands (its teleport
			// also lifts it out of any geometry), not from wherever it drifted to.
			self.sent = None;
			if let Some(back) = handback {
				log::line("collision: F8 returns the body above its scanned floor before handing control back");
				self.restore_above_floor(player, &space, back);
			}
			log::line(if self.drive { "Minecraft drives the player" } else { "Elden Ring drives the player (mirror mode)" });
		}
		if self.keys.pressed(keys::VK_F9) {
			self.first_person = !self.first_person;
			log::line(if self.first_person { "first person" } else { "third person" });
		}
		if self.keys.pressed(keys::VK_F6) {
			// Along Minecraft's look, from its eye: what the crosshair is on.
			let (yaw, pitch) = self.look.unwrap_or((0.0, 0.0));
			let (y, p) = (yaw.to_radians() as f64, pitch.to_radians() as f64);
			let dir = [-y.sin() * p.cos(), -p.sin(), y.cos() * p.cos()];
			let eye = self.link.read_mc().map(|m| m.eye).unwrap_or_default();
			log::line(&format!("F6 aim probe (filter@distance): {}", self.scanner.aim_probe(player, &space, eye, dir)));
			let h = player.chr_ins.modules.physics.position;
			let feet = self.link.read_mc().filter(|m| m.in_world).map_or_else(|| space.havok_to_mc([h.0, h.1, h.2]), |m| m.pos);
			log::line(&format!("F6 column under the player (relative to feet): {}", self.scanner.column_probe(player, &space, feet)));
		}
		if self.keys.pressed(keys::VK_F11) {
			log::line(&format!("blocks drawn with the camera from {} frame(s) back (F11)", camera::cycle_delay()));
		}
		if self.keys.pressed(keys::VK_F3) {
			self.depth_occlusion = !self.depth_occlusion;
			log::line(if self.depth_occlusion { "block occlusion: from the game's scene depth (F3)" } else { "block occlusion: from the scanned collision (F3)" });
		}
		if self.keys.pressed(keys::VK_F4) {
			self.game_light = !self.game_light;
			log::line(if self.game_light { "block light: from the game's picture" } else { "block light: from the clock only" });
		}
		if self.keys.pressed(keys::VK_F7) {
			self.show_collision = !self.show_collision;
			self.collision_draw.reset();
			log::line(&format!("collision view {}", if self.show_collision { "on" } else { "off" }));
		}

		let p = player.chr_ins.modules.physics.position;
		let er = space.havok_to_mc([p.0, p.1, p.2]);
		self.resources.at(er);
		// A settled connected map change renames the coordinates, not the body's place.
		let previous_er = self.observed.replace(er).filter(|_| space.world_id == self.world_id);
		let er_travel = previous_er.map_or(0.0, |old| dist(old, er));
		let mc = self.minecraft_state();
		if self.drive && self.mc_cache.as_ref().is_some_and(|(_, seen)| seen.elapsed() >= MOVEMENT_TIMEOUT) {
			log::line("movement: Minecraft stopped publishing state for one second; holding the body until it returns");
			movement::cancel(player, &space, self.written);
			self.teleport_minecraft(er);
		}
		// An ER interaction animation owns the body until it ends; Minecraft follows meanwhile.
		let held_was = self.interact.holds_body();
		if self.drive { self.interact.update(player); } else { self.interact.cancel(); }
		if self.drive && !held_was && self.interact.holds_body() {
			movement::cancel(player, &space, self.written);
			self.written = None;
			self.movement_lag = None;
		}
		if held_was && !self.interact.holds_body() {
			self.sent = None; // Minecraft resumes from wherever the animation left the body
		}
		let moving = self.drive && !self.interact.holds_body();
		let scan_at = if moving { self.sent.filter(|_| mc.as_ref().is_none_or(|m| m.teleport_ack != self.teleport_seq)).unwrap_or(er) } else { er };
		self.refresh_stream_collision(&space, scan_at);
		self.scanner.step(&self.link, player, &space, scan_at);

		if space.world_id != self.world_id {
			self.combat.clear(&self.link, Some(player));
			self.world_id = space.world_id;
			self.recovery = None;
			self.teleport_minecraft(er);
		}
		self.combat.player_frame(&self.link, player, &space, &nearby, mc.as_ref().is_some_and(|m| m.in_world));
		// Read after the health bridge: a temporary zero from an observed hit is repaired
		// there, while an actual MC-authorized death remains zero through native respawn.
		if player.chr_ins.modules.data.hp <= 0 || player.chr_ins.chr_flags1c5.death_flag() {
			self.saw_dead_body = true;
		}
		let mc_ready = mc.as_ref().is_some_and(|m| m.in_world && m.teleport_ack == self.teleport_seq
			&& m.tick_qpc > 0 && m.feet.iter().all(|v| v.is_finite()) && m.pos.iter().all(|v| v.is_finite()));
		let body_lag = mc.as_ref().filter(|_| mc_ready && moving).map_or(0.0, |m| dist(m.feet, er));
		if self.drive && (player.chr_ins.modules.data.hp <= 0 || player.chr_ins.chr_flags1c5.death_flag()) {
			// Return the camera and input as well as movement. Keeping Minecraft's frozen view
			// while ER handles death/respawn hides what the game is doing.
			log::line("movement: Elden Ring's player died; returning camera and controls to the game");
			movement::cancel(player, &space, self.written);
			self.drive = false;
			self.interact.cancel();
			self.teleport_minecraft(er);
		} else if moving {
			// Rendering interpolates between ticks and may legitimately sit below a newly
			// climbed surface. Only actual feet may move ER's body or trigger fall recovery.
			let m = mc.as_ref().filter(|_| mc_ready).map(|m| m.feet);
			let fell = m.and_then(|m| self.fallen_floor(player, &space, er, m));
			let game_warp = er_travel > WARP_BLOCKS && self.written.is_none_or(|w| dist(w, er) > WARP_BLOCKS);
			if self.sent.is_none() || game_warp {
				if game_warp {
					log::line(&format!("Elden Ring's body jumped {er_travel:.1} blocks away from its requested position; Minecraft follows"));
				}
				self.teleport_minecraft(er);
			} else if let (Some(m), Some(floor)) = (m, fell) {
				let back = [m[0], floor + GROUND_CLEARANCE, m[2]];
				if floor - m[1] > DEEP_RECOVERY_METERS && self.recovery.is_some_and(|(old, when)| when.elapsed() < RECOVERY_RETRY_WINDOW
					&& (old[0] - back[0]).hypot(old[2] - back[2]) < 2.0) {
					log::line("collision: the same terrain recovery failed again; lifting both players clear and staying in Minecraft mode");
					let lifted = [back[0], back[1] + 0.5, back[2]];
					self.recovery = None;
					self.restore_above_floor(player, &space, lifted);
				} else {
					log::line(&format!("collision: Minecraft crossed a native floor by {:.2} m; recovering both players to ({:.2}, {:.2}, {:.2}) and refreshing nearby collision", floor - m[1], back[0], back[1], back[2]));
					self.recovery = Some((back, Instant::now()));
					self.scanner.refresh_near(back);
					self.restore_above_floor(player, &space, back);
				}
			} else if let Some(m) = m {
				let stalled = if body_lag > MOVEMENT_LAG_METERS {
					self.movement_lag.get_or_insert_with(Instant::now).elapsed() >= MOVEMENT_TIMEOUT
				} else {
					self.movement_lag = None;
					false
				};
				if stalled {
					// A failed update must not cause an endless three-metre snap-back loop. Stop
					// driving and return control safely, with evidence of what failed in the log.
					let pending = player.chr_ins.chr_ctrl.chr_proxy_flags.position_sync_requested();
					log::line(&format!("movement: body lag {body_lag:.2} m for one second (sync pending {pending}); resyncing Minecraft to the body"));
					movement::cancel(player, &space, self.written);
					self.teleport_minecraft(er);
				} else {
					match movement::request(player, &space, m, self.written) {
						movement::Request::Queued => self.written = Some(m),
						movement::Request::Pending => {},
						movement::Request::Invalid => {
							log::line("movement: invalid Minecraft destination; resyncing Minecraft to the body");
							movement::cancel(player, &space, self.written);
							self.teleport_minecraft(er);
						}
					}
				}
			}
		} else {
			// An F8 handback can have a body rescue queued for the next update. Keep its
			// destination until the controller consumes it, rather than mirroring the old
			// underground body back into Minecraft in this same frame.
			let mirror = self.written.filter(|_| player.chr_ins.chr_ctrl.chr_proxy_flags.position_sync_requested())
				.unwrap_or(er);
			if self.sent.is_none_or(|s| dist(s, mirror) > RESYNC_BLOCKS)
				|| mc.as_ref().is_some_and(|m| mc_ready && dist(m.pos, mirror) > MIRROR_DRIFT) {
				self.teleport_minecraft(mirror);
			}
		}

		let mc_ready = mc_ready && mc.as_ref().is_some_and(|m| m.teleport_ack == self.teleport_seq);
		if moving && !mc_ready {
			let wait = self.handoff_wait.get_or_insert_with(|| (Instant::now(), false));
			if wait.0.elapsed() >= HANDOFF_TIMEOUT {
				log::line(&format!("movement: teleport {} was not acknowledged within eight seconds; retrying in Minecraft mode (MC ack {:?})",
					self.teleport_seq, mc.as_ref().map(|m| m.teleport_ack)));
				let back = self.recovery.map_or(er, |(at, _)| at);
				self.handoff_wait = None;
				self.restore_above_floor(player, &space, back);
			} else if !wait.1 && wait.0.elapsed() >= HANDOFF_REFRESH {
				wait.1 = true;
				let at = self.sent.unwrap_or(er);
				self.scanner.refresh_near(at);
				log::line(&format!("movement: teleport {} awaits terrain/server acknowledgement; refreshing its destination", self.teleport_seq));
			}
		} else {
			self.handoff_wait = None;
		}
		// Publish after mode changes/recovery so Minecraft gets a patch at the current
		// destination, not at the position from before this frame's teleport.
		let ground_at = mc.as_ref().filter(|_| moving && mc_ready).map_or(self.sent.unwrap_or(er), |m| m.feet);
		let patch = mc.as_ref().filter(|m| m.in_world).and_then(|_| self.scanner.ground_patch(player, &space, ground_at));
		self.link.publish_ground(space.world_id, self.scanner.epoch(), patch);

		// Who has the controls: Minecraft while it drives, unless an Elden Ring menu is up (the
		// game shows its cursor) or F10 handed them back.
		if self.keys.pressed(keys::VK_F10) {
			self.er_controls = !self.er_controls;
			log::line(if self.er_controls { "controls: Elden Ring (F10)" } else { "controls: Minecraft (F10)" });
		}
		let menu = keys::cursor_visible();
		if menu != self.menu_was {
			self.menu_was = menu;
			log::line(if menu { "Elden Ring shows its cursor: controls go to the game" } else { "Elden Ring hid its cursor" });
		}
		// Recoveries change teleport_seq after the earlier snapshot check. Keep MC's camera
		// and input ownership during acknowledgement; only forwarding/movement waits.
		let mc_ready = mc_ready && mc.as_ref().is_some_and(|m| m.teleport_ack == self.teleport_seq);
		let mc_visible = self.drive && mc.as_ref().is_some_and(|m| m.in_world);
		let mc_owns_input = mc_visible && keys::focused() && !menu && !self.er_controls;
		let minecraft_controls = mc_owns_input && mc_ready;
		// R taps ER's Event Action: doors, levers, pickups, graces and popup confirmation.
		if self.keys.pressed(keys::VK_R) && mc_owns_input {
			self.interact.begin(player);
		}
		dinput::set_block(mc_owns_input);
		let mouse = dinput::take();

		// During an ER interaction animation the camera rides the body itself: Minecraft is parked
		// and only re-teleported every half block, which would step the view and fight the motion.
		let follow_body = self.drive && !moving;
		let feet = if follow_body { er }
			else if self.drive && mc_ready { mc.as_ref().unwrap().pos }
			else if mc_visible { self.sent.unwrap_or(er) } else { er };
		let eye_height = mc.as_ref().map_or(1.62, |m| m.eye_height as f64);
		let eye_height = if eye_height.is_finite() { eye_height.clamp(0.2, 3.0) } else { 1.62 };
		let eye = match &mc {
			Some(m) if !follow_body && self.drive && mc_ready && m.eye.iter().all(|v| v.is_finite()) && dist(m.eye, m.pos) < 4.0 => m.eye,
			_ => [feet[0], feet[1] + eye_height, feet[2]],
		};
		let screen_open = mc.as_ref().is_some_and(|m| m.screen_open);
		self.combat.frame(&self.link, player, &space, &nearby, mc.as_ref(),
			minecraft_controls && !follow_body && !screen_open && player.chr_ins.modules.data.hp > 0);
		if minecraft_controls && mouse.wheel != 0 {
			self.link.send_input(proto::IN_SCROLL, 0, mouse.wheel as i32);
		}
		if minecraft_controls && !screen_open {
			// Minecraft's own mouse look: its sensitivity curve, applied to the raw mouse.
			if self.look.is_none() {
				self.look = Some(self.aim(player, &space, eye).unwrap_or((0.0, 0.0)));
			}
			let s = mc.as_ref().unwrap().sensitivity as f64 * 0.6 + 0.2;
			let k = s * s * s * 8.0 * 0.15;
			let look = self.look.as_mut().unwrap();
			look.0 += (mouse.dx as f64 * k) as f32;
			look.1 = (look.1 as f64 + mouse.dy as f64 * k).clamp(-90.0, 90.0) as f32;
		} else if !mc_visible {
			self.look = None;
		}
		let (yaw, pitch) = match self.look {
			Some(l) => l,
			None => self.aim(player, &space, eye).unwrap_or_else(|| {
				let o = player.chr_ins.modules.physics.orientation;
				let f = glam::Quat::from(o).mul_vec3(glam::vec3(0.0, 0.0, -1.0));
				world::yaw_pitch(world::dir_to_mc([f.x, f.y, f.z]))
			}),
		};

		// Minecraft's camera (eye, look, field of view) replaces the game's: first person, or
		// behind / in front of the player when Minecraft's F5 says so.
		let first_person = self.first_person && mc_visible;
		let fov_deg = mc.as_ref().map_or(70.0, |m| m.fov_deg);
		let (cam_eye, cam_yaw, cam_pitch) = {
			let (y, p) = ((yaw as f64).to_radians(), (pitch as f64).to_radians());
			let f = [-y.sin() * p.cos(), -p.sin(), y.cos() * p.cos()];
			match mc.as_ref().filter(|_| first_person).map(|m| (m.camera_mode, m.camera_distance as f64)) {
				Some((1, d)) => ([eye[0] - f[0] * d, eye[1] - f[1] * d, eye[2] - f[2] * d], yaw, pitch),
				Some((2, d)) => ([eye[0] + f[0] * d, eye[1] + f[1] * d, eye[2] + f[2] * d], yaw + 180.0, -pitch),
				_ => (eye, yaw, pitch),
			}
		};
		camera::set(first_person.then(|| camera::View {
			eye: space.mc_to_havok(cam_eye),
			yaw: cam_yaw,
			pitch: cam_pitch,
			fov_deg,
			mc: scene::View { eye: cam_eye, yaw: cam_yaw, pitch: cam_pitch, fov_deg },
		}));
		let driving = self.drive && mc.as_ref().is_some_and(|m| m.in_world);
		// Installed ER code: no_gravity disables native ground-contact processing. That
		// also prevents its ground geometry/map ownership from following a cave entry,
		// despite the physics and model positions moving normally. Let the native
		// controller resolve contact beside real ER ground. Do not mark an airborne or
		// MC-block-supported player grounded, or overwrite last-safe save coordinates.
		let native_contact = driving && mc_ready && body_lag <= NATIVE_CONTACT_BODY_LAG
			&& patch.is_some_and(|(_, _, heights)| {
				let gap = ground_at[1] - heights[0];
				(-0.05..=NATIVE_CONTACT_GAP).contains(&gap)
			});
		// The ray patch is measured this frame, not the cached scan's outdoor roof.
		// Missing support and every teleport acknowledgement wait keep suspension.
		if driving || self.driving_was {
			player.chr_ins.chr_flags1c4.set_no_gravity(driving && moving && !native_contact);
		}
		if driving {
			// Request the live local body's normal updates even when its model is transparent.
			// ER consumes/resets this request every frame; it is not a persistent debug mode.
			player.chr_ins.chr_flags1c4.set_force_update(true);
		}
		self.driving_was = driving;
		{
			if let Ok(data) = unsafe { GameDataMan::instance_mut() } {
				let settings = data.game_settings.as_mut();
				if driving || self.combat.owns_health() {
					if self.saved_hud.is_none() {
						self.saved_hud = Some(settings.hud_type);
					}
					settings.hud_type = HudType::Off;
				} else if let Some(hud) = self.saved_hud.take() {
					settings.hud_type = hud;
				}
			}
		}
		self.set_model_hidden(player, first_person);
		// The moved body has no locomotion animation of its own while Minecraft drives it.
		let living = player.chr_ins.modules.data.hp > 0 && !player.chr_ins.chr_flags1c5.death_flag();
		self.hearing.frame(player, [p.0, p.1, p.2], er, driving && moving && mc_ready && living, mc.as_ref());
		// ER offers only interactions the body faces: turn it with Minecraft's look.
		if driving && moving && mc_ready && living {
			self.interact.face(player, yaw);
		} else if !self.drive {
			self.interact.learn_forward(player);
		}

		let screen = screen_open.then(hud::viewport).flatten();
		// Movement/attack keys stay released while the animation owns the body (look still turns).
		self.input.poll(&self.link, minecraft_controls && !follow_body, screen, (mouse.dx, mouse.dy));
		self.link.publish(&Publish {
			flags: proto::GAME_IN_GAME | if first_person && mc.as_ref().is_some_and(|m| m.camera_mode == 0) { proto::GAME_MC_HANDS } else { 0 },
			world_id: space.world_id,
			collision_epoch: self.scanner.epoch(),
			pos: self.sent.unwrap_or(er),
			yaw,
			pitch,
			teleport_seq: self.teleport_seq,
			viewport: hud::viewport().unwrap_or((1920, 1080)),
		});
		// Elden Ring's time of day outdoors; dim and even in caves and buildings. Eased, not snapped.
		let outdoors = matches!(space.world_id >> 24, 60 | 61);
		let hour = clock_hour();
		self.light.approach(lighting::light(hour, outdoors), 0.04);
		let (light, game_light) = (self.light, self.game_light);
		// How far away the game's own ray says the ground straight ahead is: it tells the renderer how the
		// game stores its depth. Only from the eye (first person) and past a few metres, clear of our own body.
		let planes = unsafe { CSCamera::instance() }.ok().map(|c| (c.pers_cam_1.near_plane, c.pers_cam_1.far_plane))
			.filter(|(n, f)| n.is_finite() && f.is_finite() && *n > 0.0 && *f > *n * 2.0).unwrap_or((0.1, 5000.0));
		let probe = (self.depth_occlusion && first_person).then(|| {
			let (y, p) = ((cam_yaw as f64).to_radians(), (cam_pitch as f64).to_radians());
			let forward = [-y.sin() * p.cos(), -p.sin(), y.cos() * p.cos()];
			let reach = forward.map(|c| c * 120.0);
			self.scanner.ray(player, &space, cam_eye, reach).map(|hit| dist(hit, cam_eye))
		}).flatten().filter(|d| *d > 3.0 && *d < 100.0).map(|d| d as f32);
		let depth_occlusion = self.depth_occlusion;
		scene::with(|s| {
			s.light = light;
			s.game_light = game_light;
			s.depth_occlusion = depth_occlusion;
			s.depth_probe = probe;
			s.camera_planes = planes;
			s.view = first_person.then(|| scene::View { eye: cam_eye, yaw: cam_yaw, pitch: cam_pitch, fov_deg });
			s.avatar_at = first_person.then(|| if follow_body { Some(er) } else { mc.as_ref().map(|m| m.pos) }).flatten();
			let shown = mc_visible || self.combat.owns_health();
			let m = mc.as_ref();
			s.hud = scene::Hud {
				shown,
				crosshair: mc_visible && m.is_some_and(|m| m.camera_mode == 0 && !m.screen_open),
				gui_scale: m.map_or(0, |m| m.gui_scale),
				cursor: self.input.cursor().map(|(x, y)| (x as f32, y as f32)),
			};
		});
		if self.show_collision {
			self.collision_draw.draw(&space, &self.scanner, er);
		}

		let connected = self.link.mc_connected();
		if connected {
			self.waiting_for_mc = None;
		} else if self.waiting_for_mc.get_or_insert_with(Instant::now).elapsed() > MINECRAFT_CONNECT_TIMEOUT {
			self.waiting_for_mc = Some(Instant::now());
			std::thread::spawn(launcher::restart_minecraft);
		}
		if connected != self.mc_was_connected {
			self.mc_was_connected = connected;
			log::line(if connected { "Minecraft connected" } else { "Minecraft disconnected" });
		}
		let now = Instant::now();
		if now >= self.next_report {
			self.next_report = now + Duration::from_secs(2);
			let mc = match &mc {
				Some(m) if m.in_world => format!("feet ({:.2}, {:.2}, {:.2}), render ({:.2}, {:.2}, {:.2}) ack {}/{}",
					m.feet[0], m.feet[1], m.feet[2], m.pos[0], m.pos[1], m.pos[2], m.teleport_ack, self.teleport_seq),
				Some(_) => "not in a world".into(),
				None => "busy".into(),
			};
			log::line(&format!(
				"{} block {} er ({:.2}, {:.2}, {:.2}) look {:.0}/{:.0} | minecraft {} | body lag {:.2} m, sync pending {} | hp {}, mc input {}, screen {}, snapshot age {:.0} ms | cached floor {:?}",
				if self.drive { "drive" } else { "mirror" },
				player.chr_ins.block_id, er[0], er[1], er[2], yaw, pitch, mc, body_lag,
				player.chr_ins.chr_ctrl.chr_proxy_flags.position_sync_requested(),
				player.chr_ins.modules.data.hp, minecraft_controls, screen_open,
				self.mc_cache.as_ref().map_or(-1.0, |(_, seen)| seen.elapsed().as_secs_f64() * 1000.0),
				self.scanner.floor_at(er[0], er[2])
			));
			log::line(&world::streaming_report(player, native_contact, patch.map(|(_, _, heights)| ground_at[1] - heights[0])));
			log::line(&format!("lighting: clock {hour:.2} h, {}, sun dir ({:.2}, {:.2}, {:.2}) colour ({:.2}, {:.2}, {:.2}), ambient ({:.2}, {:.2}, {:.2})",
				if outdoors { "outdoors" } else { "indoors" }, light.dir[0], light.dir[1], light.dir[2],
				light.direct[0], light.direct[1], light.direct[2], light.ambient[0], light.ambient[1], light.ambient[2]));
		}
	}

	/// The mapping to use this frame, or None while it isn't steady yet.
	///
	/// The game re-centres its physics space now and then (a few seconds after a load, for one):
	/// every havok position jumps in one frame while the tile-local position stays put. That is
	/// taken at once. The tile-local position on its own can lag or be stale (loading, falling, our
	/// own writes): those jumps are ignored unless the new mapping holds for ANCHOR_FRAMES frames.
	/// A pure Havok re-centre keeps world collision. A changed world offset parks Minecraft
	/// for a frame and refreshes collision/handoff, since the previous coordinates are stale.
	/// A connected map change keeps the held mapping until the new one settles (`hold_connected`).
	fn anchor(&mut self, fresh: Space, alive: bool) -> Option<Space> {
		let prev = self.prev_fresh.replace(fresh);
		if let Some(held) = self.space.filter(|h| h.world_id == fresh.world_id) {
			if held.drift(&fresh) < REANCHOR_METERS {
				self.candidate = None;
				if self.connected_since.take().is_some() {
					log::line("maps: position returned to the held map before the new one settled");
				}
				return Some(held);
			}
			if let Some(p) = prev.filter(|p| p.world_id == fresh.world_id) {
				let havok_jump = dist(p.havok, fresh.havok);
				let world_jump = dist(p.world, fresh.world);
				if havok_jump > REANCHOR_METERS && world_jump < REBASE_WORLD_STILL {
					log::line(&format!("havok space re-centred by {havok_jump:.1} m; mapping follows"));
					self.candidate = None;
					self.connected_since = None;
					self.space = Some(fresh);
					return Some(fresh);
				}
			}
		}
		let steady = match self.candidate {
			Some((c, n)) if c.world_id == fresh.world_id && c.drift(&fresh) < ANCHOR_AGREE => n + 1,
			_ => 1,
		};
		self.candidate = Some((fresh, steady));
		if steady < ANCHOR_FRAMES {
			if let Some(held) = self.space.filter(|h| h.world_id == fresh.world_id) {
				return Some(held);
			}
			return self.hold_connected(prev, fresh, alive);
		}
		self.candidate = None;
		if let Some(since) = self.connected_since.take() {
			log::line(&format!("maps: connected change to world {:08x} settled after {} ms; Minecraft camera and controls were kept",
				fresh.world_id, since.elapsed().as_millis()));
		}
		let changed_offset = self.space.filter(|h| h.world_id == fresh.world_id).map(|h| h.drift(&fresh));
		self.space = Some(fresh);
		if let Some(drift) = changed_offset {
			log::line(&format!("mapping moved {drift:.1} m and held steady; parking Minecraft and refreshing collision/handoff"));
			// run cancels the old movement request before publish_loading discards the MC
			// snapshot; the next settled frame starts a fresh collision epoch and teleport.
			return None;
		}
		Some(fresh)
	}

	/// The previous mapping while a different map ID settles under the same live body. Havok is
	/// continuous through a connected entrance, so the old offset still places this body, its
	/// camera and its movement exactly; only the names of the coordinates are about to change.
	/// A loading screen (no map ID) never reaches here. A havok jump, death or a wait beyond
	/// CONNECTED_HOLD is not a connected walk: drop the mapping and use the normal loading path.
	fn hold_connected(&mut self, prev: Option<Space>, fresh: Space, alive: bool) -> Option<Space> {
		let Some(held) = self.space else {
			self.connected_since = None;
			return None;
		};
		let step = prev.map_or(f64::INFINITY, |p| dist(p.havok, fresh.havok));
		let since = match self.connected_since {
			Some(since) => since,
			None => {
				log::line(&format!("maps: world {:08x} -> {:08x} under the live body; holding the Minecraft view on the previous mapping while the new one settles",
					held.world_id, fresh.world_id));
				*self.connected_since.insert(Instant::now())
			}
		};
		if alive && step < CONNECTED_STEP && since.elapsed() < CONNECTED_HOLD {
			return Some(held);
		}
		log::line(&format!("maps: map change is not a connected walk (alive {alive}, havok step {step:.2} m, held {} ms); using the loading path",
			since.elapsed().as_millis()));
		self.connected_since = None;
		// Never resume a hold on this offset after the body has jumped or the wait has failed.
		self.space = None;
		None
	}

	/// Validate each downward destination while ER is still above the surface. Waiting until
	/// the player is inside a guessed solid span misses thin/open terrain and large tick drops.
	fn fallen_floor(&mut self, player: &PlayerIns, space: &Space, er: [f64; 3], m: [f64; 3]) -> Option<f64> {
		let from = self.written.unwrap_or(er);
		if let Some(floor) = self.scanner.crossed_floor(player, space, from, m) {
			if self.scanner.carried_rise(player, space, m) {
				return None;
			}
			log::line(&format!("collision: native sweep ({:.2}, {:.2}, {:.2}) -> ({:.2}, {:.2}, {:.2}) hit floor {floor:.2}; cached floor {:?}",
				from[0], from[1], from[2], m[0], m[1], m[2], self.scanner.floor_at(m[0], m[2])));
			return Some(floor);
		}
		None
	}

	/// Minecraft yaw and pitch from `eye` toward what the game camera's centre is on.
	fn aim(&mut self, player: &PlayerIns, space: &Space, eye: [f64; 3]) -> Option<(f32, f32)> {
		let camera = unsafe { CSCamera::instance() }.ok()?;
		let cam = &*camera.pers_cam_1;
		let p = cam.position();
		let f = cam.forward();
		let from = space.havok_to_mc([p.0, p.1, p.2]);
		let mut dir = world::dir_to_mc([f.0, f.1, f.2]);
		// In third person the camera looks toward the player: that tells which way the matrix's
		// forward row points. In first person the camera is in the eye, so keep what was learned.
		let to_eye = [eye[0] - from[0], eye[1] - from[1], eye[2] - from[2]];
		let dist = (to_eye[0] * to_eye[0] + to_eye[1] * to_eye[1] + to_eye[2] * to_eye[2]).sqrt();
		if dist > 1.0 && self.model_state.is_none() {
			let sign = if dir[0] * to_eye[0] + dir[1] * to_eye[1] + dir[2] * to_eye[2] < 0.0 { -1.0 } else { 1.0 };
			if sign != self.cam_sign {
				self.cam_sign = sign;
				log::line(&format!("camera forward row sign {sign}"));
			}
		}
		dir = dir.map(|d| d * self.cam_sign);
		let len = (dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]).sqrt().max(1e-9);
		let reach = dir.map(|d| d / len * AIM_REACH);
		let target = self.scanner.ray(player, space, from, reach).unwrap_or([from[0] + reach[0], from[1] + reach[1], from[2] + reach[2]]);
		Some(world::yaw_pitch([target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]]))
	}
}

/// Elden Ring's in-game time of day in hours, or noon if the clock cannot be read.
fn clock_hour() -> f32 {
	match unsafe { eldenring::cs::WorldAreaTime::instance() } {
		Ok(time) if time.clock.hours() < 24 && time.clock.minutes() < 60 => {
			time.clock.hours() as f32 + time.clock.minutes() as f32 / 60.0 + time.clock.seconds() as f32 / 3600.0
		}
		_ => 12.0,
	}
}

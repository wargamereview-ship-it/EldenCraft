package dev.eldencraft.client;

import dev.eldencraft.EldenCraft;
import dev.eldencraft.client.render.WorldExporter;
import dev.eldencraft.link.Proto;
import dev.eldencraft.link.SkyLink;
import dev.eldencraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;

/**
 * Per-frame glue between the Minecraft client and Skyrim. Everything here runs on the render
 * thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("eldencraft.showWindow");
	// Started by Skyrim (EldenCraft's bundled instance passes -Deldencraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while Skyrim is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once Skyrim is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("eldencraft.startHidden");
	private static boolean startedHidden;

	private static final SkyLink.SkyState sky = new SkyLink.SkyState();
	private static final SkyLink.McState mc = new SkyLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Teleport / hold state: Skyrim decides where the player is after loads, doors and respawns.
	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static long nextHoldReport;
	private static boolean holdGroundAligned;
	private static volatile int teleportTicket;
	private static volatile int serverTeleportCompleted = -1;
	private static int settledTeleportTicks;
	private static long qpcFreq;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean skyrimStalled;
	private static int exporterErrors;
	private static int lastRespawnEpoch;

	private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once Skyrim has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if Skyrim closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	public static SkyLink.SkyState sky() {
		return sky;
	}

	/** Start of Minecraft.runTick: pull state and input from Skyrim before anything else runs. */
	public static void beginFrame() {
		SkyLink.poll();
		quitWithSkyrim(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = SkyLink.active();
		if (nowLinked) {
			SkyLink.readSkyState(sky); // on a torn read we simply keep last frame's state
			dev.eldencraft.world.SkyWater.refresh();
		} else {
			dev.eldencraft.world.SkyWater.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			EldenCraft.LOG.info("EldenCraft: Skyrim link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				SkyCollision.startConsumer();
				applyLinkedOptions();
			} else {
				InputBridge.releaseAll();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.openWhenReady(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		var life = SkyLink.readNativeLife();
		if (life != null && life.respawn() && life.worldId() == sky.worldId && sky.inGame() && !sky.loading()
			&& player.isDeadOrDying() && lastRespawnEpoch != life.epoch()) {
			lastRespawnEpoch = life.epoch();
			player.respawn();
			minecraft.gui.setScreen(null);
			teleportPending = true;
			EldenCraft.LOG.info("EldenCraft: ER grace respawn ready; respawning Minecraft player (life epoch {})", life.epoch());
		}

		// A new player object means we just joined or respawned: put it where Skyrim's player is.
		if (player != lastPlayer) {
			lastPlayer = player;
			teleportPending = true;
			unlinkedHold = null;
		}
		if (sky.teleportSeq != lastTeleportSeq) {
			lastTeleportSeq = sky.teleportSeq;
			teleportPending = true;
		}
		if (teleportPending && sky.inGame() && !sky.loading()) {
			requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
			teleportAck = sky.teleportSeq;
			teleportPending = false;
			holdPos = new Vec3(sky.x, sky.y, sky.z);
			holdSince = System.currentTimeMillis();
			holdGroundAligned = false;
		}

		// Look direction is driven by Skyrim (zero-latency camera); MC uses it for everything else.
		if (minecraft.gui.screen() == null) {
			player.setYRot(sky.yaw);
			player.setXRot(sky.pitch);
			player.yRotO = sky.yaw;
			player.xRotO = sky.pitch;
		}
	}

	// Minecraft is started with Skyrim (the SKSE plugin launches it), so it goes when that Skyrim has
	// closed for good: saved and shut down the normal way. -Deldencraft.quitWithSkyrim=false keeps it
	// running instead (development: restarting Skyrim without restarting Minecraft).
	private static final boolean QUIT_WITH_SKYRIM = Boolean.parseBoolean(System.getProperty("eldencraft.quitWithSkyrim", "true"));
	private static long skyrimGoneSince;
	private static long nextSkyrimCheck;
	// Started hidden by Skyrim but never connected: nobody can see or use this Minecraft, and it
	// would stop the next Skyrim from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	private static void quitWithSkyrim(Minecraft minecraft) {
		int pid = SkyLink.skyrimPid();
		long now = System.currentTimeMillis();
		if (QUIT_WITH_SKYRIM && START_HIDDEN && pid == 0 && !tookOver && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			EldenCraft.LOG.warn("EldenCraft: started hidden but Skyrim never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_SKYRIM || pid == 0 || now < nextSkyrimCheck) {
			return;
		}
		nextSkyrimCheck = now + 1000;
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			skyrimGoneSince = 0;
			return;
		}
		if (skyrimGoneSince == 0) {
			skyrimGoneSince = now;
		} else if (now - skyrimGoneSince > 5000) {
			EldenCraft.LOG.info("EldenCraft: Skyrim (pid {}) has closed; saving and quitting", pid);
			minecraft.stop();
		}
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		MirrorWorld.tick(minecraft);
		DiscordPresence.tick(minecraft);
		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		publishTick(minecraft);
		watchServer(minecraft);
	}

	private static final long SERVER_STALL_MS = 10_000;
	private static boolean stallReported;

	/**
	 * Handoffs complete on the integrated server. If it stops ticking while the client runs,
	 * record every thread's stack once: a stall otherwise only shows as "server false" holds.
	 */
	private static void watchServer(Minecraft minecraft) {
		var server = minecraft.getSingleplayerServer();
		long last = dev.eldencraft.combat.SkyCombat.lastServerTickMs;
		if (server == null || last == 0 || minecraft.player == null) {
			stallReported = false;
			return;
		}
		long stalled = System.currentTimeMillis() - last;
		if (stalled < SERVER_STALL_MS) {
			if (stallReported) {
				EldenCraft.LOG.info("EldenCraft: integrated server ticks again");
			}
			stallReported = false;
			return;
		}
		if (stallReported) {
			return;
		}
		stallReported = true;
		StringBuilder dump = new StringBuilder();
		for (var entry : Thread.getAllStackTraces().entrySet()) {
			Thread thread = entry.getKey();
			dump.append("\n\"").append(thread.getName()).append("\" ").append(thread.getState());
			for (StackTraceElement frame : entry.getValue()) {
				dump.append("\n\tat ").append(frame);
			}
		}
		EldenCraft.LOG.warn("EldenCraft: integrated server has not ticked for {} ms (paused {}, running {}); thread dump:{}",
			stalled, minecraft.isPaused(), server.isRunning(), dump);
	}

	/**
	 * Skyrim went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (interior doors), so keep the player exactly where they were
	 * instead of letting them fall; Skyrim puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		park(player, unlinkedHold);
	}

	/**
	 * Hands Skyrim the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * QueryPerformanceCounter timestamp. Skyrim interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (qpcFreq == 0) {
			qpcFreq = SkyLink.qpcFrequency();
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		mc.tickQpc = SkyLink.qpc() - (long) (remainder * tickMs * qpcFreq / 1000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
		var avatar = player.avatarState();
		mc.walkDistO = bob ? avatar.getInterpolatedWalkDistance(0.0F) : 0.0F;
		mc.walkDist = bob ? avatar.getInterpolatedWalkDistance(1.0F) : 0.0F;
		mc.bobO = bob ? avatar.getInterpolatedBob(0.0F) : 0.0F;
		mc.bob = bob ? avatar.getInterpolatedBob(1.0F) : 0.0F;
		SkyLink.writeMcState(mc);
	}

	/** Freeze the player until Skyrim's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// Skyrim is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		var box = player.getBoundingBox().move(holdPos.subtract(player.position()));
		boolean known = SkyCollider.terrainReady(box, Vec3.ZERO);
		double maxAbove = player.maxUpStep() + 0.05;
		double ground = known ? SkyCollider.groundAt(holdPos.x, holdPos.y, holdPos.z, maxAbove) : Double.NaN;
		// Actual Minecraft blocks can also support a handoff (e.g. a platform we built).
		boolean blockSupport = Entity.collideBoundingBox(player, new Vec3(0, -0.1, 0), box, player.level(), java.util.List.of()).y > -0.1;
		boolean ready = known && (!Double.isNaN(ground) || blockSupport);
		if (ready && sky.inGame() && !sky.loading()) {
			if (!holdGroundAligned) {
				holdGroundAligned = true;
				// Only a walkable surface within step height may lift the feet. Searching 2.5 m
				// above them could select a tunnel roof or the underside of a nearby structure.
				if (!Double.isNaN(ground) && ground > holdPos.y + 0.005) {
					holdPos = new Vec3(holdPos.x, ground + 0.01, holdPos.z);
					requestTeleport(minecraft, holdPos.x, holdPos.y, holdPos.z, sky.yaw, sky.pitch);
				}
			}
			if (serverTeleportCompleted == teleportTicket) {
				// Let the local server's teleport packet reach the client while still parked;
				// it must not put us back at the old feet after the native body starts following.
				if (++settledTeleportTicks >= 2) {
					park(player, holdPos);
					EldenCraft.LOG.info("EldenCraft: teleport {} ready at {} (epoch {}, wait {} ms)",
						teleportAck, holdPos, sky.collisionEpoch, System.currentTimeMillis() - holdSince);
					holdPos = null;
					return;
				}
			} else {
				settledTeleportTicks = 0;
			}
		} else {
			settledTeleportTicks = 0;
		}
		if (System.currentTimeMillis() >= nextHoldReport && System.currentTimeMillis() - holdSince >= 2000) {
			nextHoldReport = System.currentTimeMillis() + 2000;
			EldenCraft.LOG.info("EldenCraft: holding teleport {}: regions {}, ground {}, server {}, epoch {}",
				teleportAck, known, ground, serverTeleportCompleted == teleportTicket, sky.collisionEpoch);
		}
		park(player, holdPos);
	}

	/** A teleport/hold is a discontinuity; neither rendered feet nor published physics may lerp through it. */
	private static void park(LocalPlayer player, Vec3 pos) {
		player.setPos(pos.x, pos.y, pos.z);
		player.xo = pos.x;
		player.yo = pos.y;
		player.zo = pos.z;
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		mc.prevX = mc.curX = pos.x;
		mc.prevY = mc.curY = pos.y;
		mc.prevZ = mc.curZ = pos.z;
		mc.tickQpc = SkyLink.qpc();
		mc.tickMs = 50.0F;
	}

	private static void requestTeleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		park(player, new Vec3(x, y, z));
		int ticket = ++teleportTicket;
		serverTeleportCompleted = -1;
		settledTeleportTicks = 0;
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null && teleportTicket == ticket) {
					sp.teleportTo(x, y, z);
					sp.setDeltaMovement(Vec3.ZERO);
					sp.setYRot(yaw);
					sp.setXRot(pitch);
					sp.resetFallDistance();
					serverTeleportCompleted = ticket;
				}
			});
		} else {
			serverTeleportCompleted = ticket;
		}
		EldenCraft.LOG.info("EldenCraft: teleported to {} {} {}", x, y, z);
	}

	/** After GameRenderer.render(): report the player to Skyrim and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.mainCamera();
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.position();
			mc.eyeHeight = holdPos == null ? (float) (eye.y - feet.y) : player.getEyeHeight();
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = camera.getFov();
			// Minecraft's F5 camera: Skyrim puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			mc.cameraDistance = camera.isDetached() ? (float) camera.position().distanceTo(player.getEyePosition(partial)) : 0.0F;
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
			var entityState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.entityRenderState;
			boolean bob = minecraft.options.bobView().get() && entityState.isPlayer;
			mc.bobPhase = bob ? entityState.backwardsInterpolatedWalkDistance : 0.0F;
			mc.bobAmount = bob ? entityState.bob : 0.0F;
		}
		if (minecraft.gui.screen() != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until we are released
		mc.guiScale = minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		SkyLink.writeMcState(mc);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
			try {
				WorldExporter.frame(minecraft, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					EldenCraft.LOG.error("EldenCraft: world export failed", e);
				}
			}
			FrameExporter.capture(minecraft);
		}
	}

	/** End of the frame: render at most once per Skyrim frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			return;
		}
		if (skyrimStalled && (SkyLink.skyStateSeq() >>> 1) == lastPacedSeq) {
			return; // Skyrim is paused (menu / alt-tab): don't block every frame waiting for it
		}
		skyrimStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// SkyState.seq advances by 2 per Skyrim frame (odd while writing).
		while ((SkyLink.skyStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = SkyLink.skyStateSeq() >>> 1;
		skyrimStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		options.vignette().set(false);
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and Skyrim NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
		EldenCraft.LOG.info("EldenCraft: game window hidden (run with -Deldencraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
		int w = sky.viewportW;
		int h = sky.viewportH;
		if (w > 0 && h > 0 && !Proto.overlayFits(w, h)) {
			// Too many pixels for a slot: keep the aspect ratio, scale down to fit.
			double scale = Math.sqrt((double) Proto.MAX_OVERLAY_W * Proto.MAX_OVERLAY_H / ((double) w * h));
			w = Math.min(Proto.MAX_OVERLAY_SIDE, (int) (w * scale));
			h = Math.min(Proto.MAX_OVERLAY_SIDE, (int) (h * scale));
		}
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		minecraft.getWindow().setWindowed(w, h);
		EldenCraft.LOG.info("EldenCraft: sizing overlay to Skyrim viewport {}x{}", w, h);
	}
}

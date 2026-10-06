# Block occlusion by Elden Ring's real depth — solved

**Solved 6 October 2026 (second session), seen in play by the user.** The copy and calibration below were right all
along. The bug was the shader's distance (`1.0 / i.pos.w` instead of `i.pos.w`; see the "Fixed" note under "Shade").
Pillars, ruins and grass now hide blocks, and geometry the game fades out near the camera lets blocks show through in
its dither pattern. The rest of this document is the investigation as it stood before the fix, kept for reference.

Written 6 October 2026 for a fresh session. Read `Handoff.MD` first for the project (what EldenCraft is, how to
build and install, the working rules); this document covers one problem only.

## The problem

Minecraft blocks and entities are drawn on top of Elden Ring's finished frame. They must be hidden by anything
Elden Ring draws in front of them. They are not, for **bushes/grass and some objects, and pillars** (the user's
words: "still broken same thing, bushes and pillars"). Blocks appear painted over scenery that should hide them.

The user's requirement, verbatim: *"it should occlude through geometry not collision"*. The original occlusion
used scanned **collision** triangles drawn into a depth buffer of ours (`scan.rs` → `scene.occluders` → the
occluder pass in `gpu.rs::draw_world`). Collision is not what the game draws: it skips bushes entirely (the scan's
ray filter 0x2 "sees ground, walls and rock but not bushes"), misses props, and sits up to ~6% (about 0.2 m at 3 m)
away from the visible surfaces. So the work is to hide blocks with the game's **own depth buffer**.

Related symptom the user also reported: some nodes (logs/stone) look like they float above the visible ground.
Placement matches the native *collision* floor to within 0.2–0.3 m (logged), so this is the same collision-versus-
visual gap, not a placement bug. See "Nodes floating" below.

Screenshots (in this folder): `1.webp` — a stone node drawn over the grace's glow and floating; `2.webp` — logs
beside a pillar (the pillar does hide them correctly in this shot, a bush/grass does not), foreground log floating.
The user also sent two more that were not saved (a grace at screen centre with logs flanking a pillar; bushes in
front of a log).

## What exists now (all uncommitted; `main` is at `b1fff4f`)

Occlusion by the game's depth is **on by default**; **F3** switches it off (a hidden fallback, the user finds a
toggle silly). It fails soft: if anything below does not work the renderer logs a `depth:` line and keeps using
the collision occluders. Files:

| File | Role |
| --- | --- |
| `game/src/depth.rs` | Hooks `ID3D12GraphicsCommandList::ResourceBarrier` (MinHook via `hudhook::mh`), finds the scene depth, copies it |
| `game/src/depth_math.rs` | Depth encodings (standard / reversed / reversed-infinite), `distance`, `encode`, `calibrate`. Tested by `tools/test_depth.rs` |
| `game/src/gpu.rs` | Our copy texture + SRV (`DEPTH_SLOT` 65), root parameter 3 (PS table, register t2), calibration readback, `depth` constants, the `PSBlock` discard, skipping the collision occluders when active |
| `game/src/lib.rs` | `depth_occlusion` flag and F3, the calibration "probe" (native ray straight ahead), reads the camera near/far planes |
| `game/src/scene.rs` | `depth_occlusion`, `depth_probe`, `camera_planes` |
| `tools/shader_check/` | `check_shaders.sh` compiles every HLSL entry point with Wine's d3dcompiler (the game does this at start-up; an error there disables all block rendering). If you add a shader, add its entry point to the table in `compile_shaders.c` |
| `Handoff.MD` | Section "Occlusion by the game's real depth" |

### How it works

1. **Find the depth.** The hook's `observe()` looks at every transition barrier. The first resource moved into
   `DEPTH_WRITE` that is a single-sample 2D texture exactly the back buffer's size, with a readable depth format,
   becomes `SCENE`. Always found: **2560×1440, format 19 = `D32_FLOAT_S8X24_UINT`**.
2. **Copy it.** Two copies, both `CopyResource` recorded *inside the game's own command list* from the hook (so the
   game's queue orders them and the resource state is known):
   - right after the game makes the whole depth readable (state `0xc0` = PIXEL|NON_PIXEL SRV, `Subresource == ALL`).
     It fires about once a frame (600 copies per ~10 s), but most of the game's depth barriers are **per-plane**
     (subresource 0 = depth, 1 = stencil) and do not trigger it, and it happens mid-frame, so it can miss anything
     drawn later;
   - **at the end of the frame**, when a swap-chain back buffer is moved to `PRESENT` (`note_back_buffer` registers the
     buffers each frame). It copies from the states tracked in `PLANE_STATE[0..2]`, using one transition when both planes
     agree and one per plane otherwise.
3. **Our texture** is `R32G8X24_TYPELESS`, SRV format `R32_FLOAT_X8X24_TYPELESS`, kept in `COPY_READ`
   (PIXEL|NON_PIXEL) between copies. `ensure_scene_depth` creates it and a 256-byte readback buffer.
4. **Calibrate.** Unknown at first how the game stores depth. While `depth_mode == 0` the renderer copies a row of
   64 depth values through the middle of the picture into the readback buffer; next frame `read_depth_probe` takes
   the median of the middle five and compares it with the distance of a native collision ray through the same pixel
   (`lib.rs`, first person only, 3–100 m hits) using the camera's near/far. First fit within 3% sets the mode.
5. **Shade.** `PSBlock` (see `gpu.rs` SHADERS):

   ```hlsl
   if (depthInfo.z > 0.5 && i.pos.w > 1.5) {                  // not within 1.5 m of the camera
       float d = erDepth.Load(int3(int2(i.pos.xy), 0));
       dist = mode 1: f*n/(f - d*(f-n)); mode 2: f*n/(n + d*(f-n)); mode 3: n/max(d,1e-7);
       float mine = i.pos.w;                                    // D3D: SV_Position.w = clip.w = forward distance
       if (dist < mine - depthInfo.w * (1.0 + mine*0.02)) discard;   // bias 0.1 m + 2%
   }
   ```

   `depth_ready` (all of: switch on, mode in 1..=3, at least one copy recorded, copy texture exists) also makes the
   collision-occluder pass skip.

   **Fixed 6 October 2026 (second session), not yet seen in game:** the shader used to take `1.0 / i.pos.w` as
   the distance (OpenGL's `gl_FragCoord.w` convention). In D3D, `SV_Position.w` in a pixel shader is clip w itself
   (DXVK and vkd3d take the reciprocal of Vulkan's FragCoord.w to provide it). So the test ran only for blocks
   nearer than ~0.67 m. Every other block went untested, and because `depth_ready` also skips the collision
   occluders, nothing hid blocks at all while game-depth occlusion was on. A log line `depth: blocks are now / are
   not hidden by the game's depth (...)` now records each change of `depth_ready`.

### Evidence from the logs (native log `game/target/x86_64-pc-windows-msvc/release/eldencraft.log`)

```
depth: watching the game's resource barriers to find its scene depth
depth: found the game's scene depth (2560x1440, format 19)
depth: the scene depth's barriers over one frame: 0xc0->0x10 sub 1, 0xc0->0x10 sub 0, 0xc0->0x10 sub 1,
    0x10->0xc0 sub 0, 0x10->0xc0 sub 1, 0xc0->0x10 sub 0, 0xc0->0x10 sub 1, 0x10->0xc0 sub 0, ... (repeats ~30 times)
depth: first end-of-frame copy of the scene depth (planes were in states 0xc0 and 0xc0)
depth: first copy of the game's scene depth recorded (it was in state 0xc0)
depth: the game's depth is encoded as mode 2 (stored 0.006343, true distance 7.82 m, near 0.050067067, far 10000)   [run A]
depth: attempt 1: stored 0.017347 does not fit true distance 3.07 m ...   (attempts 1-6)
depth: the game's depth is encoded as mode 3 (stored 0.010427, true distance 4.81 m, near 0.050067067, far 10000)   [run B]
depth: 600 copies recorded
```

Established facts:

- The hook installs and runs for whole sessions with no crash or visible corruption (1,200+ in-list copies in 20 s).
- The depth is **reversed-Z**, near 0.05007, far 10000 (the camera's `near_plane`/`far_plane`); modes 2 and 3 are
  indistinguishable at these distances (n/d vs n·f/(n+d(f−n)) differ ~0.1%).
- The game flips both depth planes between `0xc0` (read as SRV) and `0x10` (`DEPTH_WRITE`) about **30 times per frame**.
  That is many separate passes using the depth both as a test target and a readable texture. Which of those passes
  are the opaque scene, the vegetation, the particles, or something that *reuses* the buffer is unknown.
- The first six calibration attempts misfit by about 6% (stored 0.0173 → n/d = 2.89 m vs a 3.07 m collision ray) before
  one fit within 3%: collision and the visible depth really do differ by that much.
- The user toggles F3 repeatedly during sessions (on/off pairs in the log) and several sessions **end with it off**, so
  in any screenshot it is not certain which mode was active. The log lines `block occlusion: from the game's scene
  depth (F3)` / `...scanned collision (F3)` give the state over time.

### What is NOT known (the actual open questions)

1. **Does the copy contain the pillars and bushes?** Nobody has seen the copy's contents. This is the first thing to
   find out. If the pillar and bush pixels are in the depth, the failure is in the shader/bias/coordinates/active-state;
   if they are not, it is about *when* the copy is taken or *what the game draws into depth*.
2. **Is the frame-end copy the right moment?** The game may reuse the depth texture after the scene (UI, outlines,
   post effects), so by `PRESENT` it could hold something else, or be cleared. The tracked plane states come from
   barriers in *recording* order across the game's threads, which may not be execution order.
3. **Is vegetation in the depth at all?** If bushes are alpha-blended, temporally dithered, or drawn with depth writes
   off, no depth copy will ever contain them and a different technique is needed.
4. **Is `depth_ready` true while drawing?** It is not logged. A silent fall-back to collision occlusion would look
   exactly like "unchanged".
5. Whether the `1.5 m` near exemption or the bias hides or reveals things wrongly (unlikely for pillars at distance).

## Suggested next steps

1. **Make the depth visible** (this was the next thing I was about to build). Add a debug view (e.g. F2): a
   full-screen pixel shader that samples `erDepth` and colours it by distance (bands every 2 m, fade to far), drawn
   over the picture. One screenshot then shows whether pillars and bushes are in the copy. Plan: new `PSDepthView`
   in the shader string, `Blend::Alpha` PSO with `vs_full` (like `pso_overlay`), a `depth_view` flag in `scene.rs`,
   drawn in `frame()` inside the overlay section (viewport is the full back buffer there) with `c.depth` set. Also
   log `depth_ready`, the mode and the copy counters every few seconds.
2. **Dump a depth frame** as a PGM from a full readback (the copy is 14 MB) for offline inspection; compare against a
   screenshot of the same frame.
3. Decide by what step 1 shows:
   - Pillars/bushes present but blocks still show → debug the comparison (state, constants, bias, the F3 state).
   - Absent → find the right copy moment from the barrier trace (log barriers *with* the draws around them, or hook
     `OMSetRenderTargets`/`ClearDepthStencilView` to see which passes write the depth and when it is cleared) and copy
     after the last pass that writes the geometry you need; or capture a different resource (a linear-depth / G-buffer
     texture the game reads for lighting).
   - Vegetation never in depth → hide blocks using the game's colour output instead? or accept and document.
4. Once occlusion is right, revisit **node placement** against the visible ground (below).

## Nodes floating

Placement (`ResourceNodes.java`, `ResourceRules.restHeight`) rests blocks on the native *collision* floor. Logged
examples: `node wood at 10723 93 -9320: native ground 93.20, block bottom 93.00`; `node rock ... ground 95.77,
bottom 96.00`. If the visible ground is lower than collision, the block floats; the user sees ~0.5–1 m gaps. With a
correct depth copy the visible surface height under a spot could be read back and used instead of (or to correct)
the collision floor. Not started.

## Practical notes

- Build the DLL: `tools/build_dll.sh --offline` (output `game/target/x86_64-pc-windows-msvc/release/eldencraft.dll`,
  loaded directly by me3). Java: see `Handoff.MD` (gradle command, `--offline`).
- Tests: `rustc --edition 2021 --test tools/test_depth.rs -o /tmp/eldencraft-depth-test && /tmp/eldencraft-depth-test`;
  `tools/shader_check/check_shaders.sh` after any shader edit.
- **Never replace the DLL/JAR while the game is running** (I did once; Minecraft read a swapped JAR). Check
  `pgrep -af eldenring.exe` first (filter out your own shell). The DLL only loads at launch; the JAR is copied into the
  Prism instance `mods` folder (path in `Handoff.MD`).
- The user plays on Linux (Proton/vkd3d-proton), which tolerates wrong barrier states more than native D3D12 would;
  a state mistake may not show up here yet still be wrong.
- Native log lines are the main evidence; ask the user to relaunch and send the log after each experiment. They answer
  in plain chat and prefer one decision at a time; do not claim anything works until they have seen it.
- Do not commit unless asked. Nothing from this session's lighting, depth, grace, elemental-enchantment or placement
  work is committed.

//! Elden Ring's own scene depth, so Minecraft's blocks are hidden by what the game actually draws
//! (buildings, terrain meshes, props) instead of the scanned collision.
//!
//! The game's depth buffer is found by watching its resource barriers (`ResourceBarrier` on the
//! command list, hooked): the first full-size depth texture it moves into the depth-write state is
//! the scene's. When the game later makes that texture readable (it does, for ambient occlusion
//! and similar), a copy of it into a texture of ours is recorded into the game's own command list
//! right there, so the copy is ordered by the game's own queue and the texture's state is known
//! exactly (no guessing at present time). The block shader reads that copy.
//!
//! Everything here is off until `ACTIVE`, and the hook is only installed on the first request.

use std::cell::Cell;
use std::ffi::c_void;
use std::mem::transmute;
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering::Relaxed};

use hudhook::mh::{MH_CreateHook, MH_EnableHook, MH_STATUS};
use hudhook::util::{create_barrier, drop_barrier};
use hudhook::windows::Win32::Graphics::Direct3D12::*;
use hudhook::windows::Win32::Graphics::Dxgi::Common::*;
use hudhook::windows::core::Interface;

use crate::{hud, log};

/// Reading states a depth texture is put in by the game for sampling.
const READ_STATES: u32 = D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE.0 as u32 | D3D12_RESOURCE_STATE_NON_PIXEL_SHADER_RESOURCE.0 as u32;
/// The state our copy sits in between frames: readable by any shader.
pub const COPY_READ: D3D12_RESOURCE_STATES = D3D12_RESOURCE_STATES(READ_STATES as i32);
const ALL_SUBRESOURCES: u32 = 0xFFFF_FFFF;

type BarrierFn = unsafe extern "system" fn(*mut c_void, u32, *const D3D12_RESOURCE_BARRIER);

static ORIGINAL: AtomicUsize = AtomicUsize::new(0);
static INSTALLED: AtomicBool = AtomicBool::new(false);
/// Set by the renderer from the player's switch (F3); the hook does nothing while it is false.
pub static ACTIVE: AtomicBool = AtomicBool::new(false);
/// The game's scene depth resource (an address, only compared; a barrier naming it proves it lives).
static SCENE: AtomicUsize = AtomicUsize::new(0);
static SCENE_FORMAT: AtomicU32 = AtomicU32::new(0);
static SCENE_SIZE: AtomicU32 = AtomicU32::new(0);
/// Owning reference, locked throughout command recording. Clearing it waits for hooks already
/// recording a copy; a concurrent renderer reset cannot invalidate a borrowed COM pointer.
static COPY: std::sync::Mutex<Option<ID3D12Resource>> = std::sync::Mutex::new(None);

/// Written beside the log when a graphics frame failed while the capture was running. Native Windows
/// drivers crashed in this path (issue #3): the capture is on everywhere, but a machine where it failed
/// once starts without it from then on, instead of crashing at every launch. Delete the file to retry.
fn marker() -> std::path::PathBuf {
	crate::log::beside_dll("eldencraft_depth_off.txt")
}

pub fn supported() -> bool {
	static SUPPORTED: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
	*SUPPORTED.get_or_init(|| !marker().exists())
}

/// Remembers a failure for later launches (see `marker`).
pub fn switch_off_for_later_launches() {
	let _ = std::fs::write(marker(), "A graphics frame failed while EldenCraft was copying the game's depth, so this is off from now on.\r\nDelete this file to try again (F3 toggles it in game).\r\n");
}

/// Where the marker lives, for messages.
pub fn marker_path() -> String {
	marker().display().to_string()
}

/// Stop recording before retiring a renderer. Submitted game lists may still reference its
/// resources, so the caller must retain the renderer rather than immediately free it.
pub fn stop() {
	ACTIVE.store(false, Relaxed);
	set_copy(None);
}
/// Copies recorded so far.
pub static COPIES: AtomicU32 = AtomicU32::new(0);
static NOTED: AtomicU32 = AtomicU32::new(0);
/// Where the scene depth's two planes (depth, stencil) were last put, as the game's barriers say.
static PLANE_STATE: [AtomicU32; 2] = [AtomicU32::new(0), AtomicU32::new(0)];
static PLANES_SEEN: AtomicBool = AtomicBool::new(false);
/// The swap chain's back buffers: the game moving one to PRESENT is the end of its frame.
static BACK: [AtomicUsize; 8] = [const { AtomicUsize::new(0) }; 8];
/// Copies taken at the end of the game's frame, and one frame's worth of the depth's barriers (for the log).
pub static END_COPIES: AtomicU32 = AtomicU32::new(0);
static TRACE: std::sync::Mutex<Vec<String>> = std::sync::Mutex::new(Vec::new());
static TRACED: AtomicBool = AtomicBool::new(false);

thread_local! { static INSIDE: Cell<bool> = const { Cell::new(false) }; }

/// (typeless format for our copy, format to read it as) for a depth format, if it can be read.
pub fn formats(format: DXGI_FORMAT) -> Option<(DXGI_FORMAT, DXGI_FORMAT)> {
	match format {
		DXGI_FORMAT_D32_FLOAT | DXGI_FORMAT_R32_TYPELESS => Some((DXGI_FORMAT_R32_TYPELESS, DXGI_FORMAT_R32_FLOAT)),
		DXGI_FORMAT_D32_FLOAT_S8X24_UINT | DXGI_FORMAT_R32G8X24_TYPELESS => Some((DXGI_FORMAT_R32G8X24_TYPELESS, DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS)),
		DXGI_FORMAT_D24_UNORM_S8_UINT | DXGI_FORMAT_R24G8_TYPELESS => Some((DXGI_FORMAT_R24G8_TYPELESS, DXGI_FORMAT_R24_UNORM_X8_TYPELESS)),
		DXGI_FORMAT_D16_UNORM | DXGI_FORMAT_R16_TYPELESS => Some((DXGI_FORMAT_R16_TYPELESS, DXGI_FORMAT_R16_UNORM)),
		_ => None,
	}
}

/// The game's scene depth, once seen: (format, width, height).
pub fn scene() -> Option<(DXGI_FORMAT, u32, u32)> {
	(SCENE.load(Relaxed) != 0).then(|| {
		let size = SCENE_SIZE.load(Relaxed);
		(DXGI_FORMAT(SCENE_FORMAT.load(Relaxed) as i32), size >> 16, size & 0xFFFF)
	})
}

/// Remembers a swap chain back buffer (the renderer calls this every frame).
pub fn note_back_buffer(buffer: &ID3D12Resource) {
	let address = buffer.as_raw() as usize;
	for slot in &BACK {
		let held = slot.load(Relaxed);
		if held == address {
			return;
		}
		if held == 0 && slot.compare_exchange(0, address, Relaxed, Relaxed).is_ok() {
			return;
		}
	}
}

/// Tells the hook where to copy to (null stops copying).
pub fn set_copy(resource: Option<&ID3D12Resource>) {
	*COPY.lock().unwrap_or_else(|e| e.into_inner()) = resource.cloned();
}

pub fn copies() -> u32 {
	COPIES.load(Relaxed)
}

/// Hooks `ID3D12GraphicsCommandList::ResourceBarrier`. A command list of the device gives the
/// address of the function every list uses.
pub unsafe fn install(device: &ID3D12Device) -> bool {
	if !supported() {
		return false;
	}
	if INSTALLED.load(Relaxed) {
		return ORIGINAL.load(Relaxed) != 0;
	}
	INSTALLED.store(true, Relaxed);
	let result = (|| -> Result<(), String> {
		unsafe {
			let allocator: ID3D12CommandAllocator = device.CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT).map_err(|e| e.to_string())?;
			let list: ID3D12GraphicsCommandList = device.CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, &allocator, None).map_err(|e| e.to_string())?;
			list.Close().map_err(|e| e.to_string())?;
			let target = (list.vtable().ResourceBarrier as usize) as *mut c_void;
			let mut trampoline = null_mut();
			MH_CreateHook(target, barrier_hook as *const () as *mut c_void, &mut trampoline).ok().map_err(|e: MH_STATUS| format!("create {e:?}"))?;
			ORIGINAL.store(trampoline as usize, Relaxed);
			MH_EnableHook(target).ok().map_err(|e: MH_STATUS| format!("enable {e:?}"))?;
		}
		Ok(())
	})();
	match result {
		Ok(()) => {
			log::line("depth: watching the game's resource barriers to find its scene depth");
			true
		}
		Err(e) => {
			log::line(&format!("depth: cannot hook the game's resource barriers ({e}); blocks keep the scanned-collision occlusion"));
			ORIGINAL.store(0, Relaxed);
			false
		}
	}
}

unsafe extern "system" fn barrier_hook(list: *mut c_void, count: u32, barriers: *const D3D12_RESOURCE_BARRIER) {
	unsafe {
		let original: BarrierFn = transmute(ORIGINAL.load(Relaxed));
		original(list, count, barriers);
		if !ACTIVE.load(Relaxed) || barriers.is_null() || count == 0 || count > 4096 {
			return;
		}
		if INSIDE.with(|inside| inside.replace(true)) {
			return;
		}
		// A panic must not unwind into the game.
		let _ = std::panic::catch_unwind(|| observe(list, std::slice::from_raw_parts(barriers, count as usize)));
		INSIDE.with(|inside| inside.set(false));
	}
}

unsafe fn observe(list: *mut c_void, barriers: &[D3D12_RESOURCE_BARRIER]) {
	for barrier in barriers {
		if barrier.Type != D3D12_RESOURCE_BARRIER_TYPE_TRANSITION {
			continue;
		}
		let transition = unsafe { &barrier.Anonymous.Transition };
		let resource: *mut c_void = unsafe { transmute_copy_ptr(&transition.pResource) };
		if resource.is_null() {
			continue;
		}
		let scene = SCENE.load(Relaxed);
		if scene == resource as usize {
			// Keep track of where the depth is, so the end of the frame can copy it from there.
			let after = transition.StateAfter.0 as u32;
			match transition.Subresource {
				ALL_SUBRESOURCES => PLANE_STATE.iter().for_each(|p| p.store(after, Relaxed)),
				0 | 1 => PLANE_STATE[transition.Subresource as usize].store(after, Relaxed),
				_ => {}
			}
			PLANES_SEEN.store(true, Relaxed);
			if !TRACED.load(Relaxed) {
				if let Ok(mut trace) = TRACE.lock() {
					if trace.len() < 64 {
						trace.push(format!("{:#x}->{:#x} sub {}", transition.StateBefore.0, after, transition.Subresource as i32));
					}
				}
			}
			if after & READ_STATES != 0 && transition.Subresource == ALL_SUBRESOURCES {
				unsafe { copy_scene_depth(list, resource, transition.StateAfter) };
			}
		} else if scene == 0 && transition.StateAfter == D3D12_RESOURCE_STATE_DEPTH_WRITE {
			unsafe { consider(resource) };
		} else if scene != 0 && transition.StateAfter == D3D12_RESOURCE_STATE_PRESENT && BACK.iter().any(|b| b.load(Relaxed) == resource as usize) {
			// The game is done drawing this frame: take the depth as it stands now, vegetation and all.
			if !TRACED.swap(true, Relaxed) {
				if let Ok(trace) = TRACE.lock() {
					log::line(&format!("depth: the scene depth's barriers over one frame: {}", trace.join(", ")));
				}
			}
			unsafe { copy_at_frame_end(list, scene as *mut c_void) };
		}
	}
}

/// The raw interface pointer inside a barrier's resource field.
unsafe fn transmute_copy_ptr<T>(field: &T) -> *mut c_void {
	unsafe { std::ptr::read(field as *const T as *const *mut c_void) }
}

/// A resource just moved into the depth-write state: it is the scene's depth if it is a
/// single, full-size, readable depth texture.
unsafe fn consider(resource: *mut c_void) {
	let Some(res) = (unsafe { ID3D12Resource::from_raw_borrowed(&resource) }) else { return };
	let desc = unsafe { res.GetDesc() };
	let Some((width, height)) = hud::viewport() else { return };
	if desc.Dimension != D3D12_RESOURCE_DIMENSION_TEXTURE2D || desc.SampleDesc.Count != 1 || desc.DepthOrArraySize != 1 || desc.MipLevels != 1
		|| desc.Width as u32 != width || desc.Height != height || formats(desc.Format).is_none() {
		return;
	}
	SCENE_FORMAT.store(desc.Format.0 as u32, Relaxed);
	SCENE_SIZE.store((width << 16) | (height & 0xFFFF), Relaxed);
	SCENE.store(resource as usize, Relaxed);
	log::line(&format!("depth: found the game's scene depth ({}x{}, format {})", width, height, desc.Format.0));
}

/// Records, into the game's own list right after it made the depth texture readable, a copy of it
/// into ours and back to the state the game put it in.
unsafe fn copy_scene_depth(list: *mut c_void, depth: *mut c_void, after: D3D12_RESOURCE_STATES) {
	let target = COPY.lock().unwrap_or_else(|e| e.into_inner());
	let Some(copy) = target.as_ref() else { return };
	unsafe {
		let (Some(commands), Some(source)) = (
			ID3D12GraphicsCommandList::from_raw_borrowed(&list),
			ID3D12Resource::from_raw_borrowed(&depth),
		) else { return };
		let original: BarrierFn = transmute(ORIGINAL.load(Relaxed));
		let before = [
			create_barrier(source, after, D3D12_RESOURCE_STATE_COPY_SOURCE),
			create_barrier(copy, COPY_READ, D3D12_RESOURCE_STATE_COPY_DEST),
		];
		original(list, before.len() as u32, before.as_ptr());
		commands.CopyResource(copy, source);
		let back = [
			create_barrier(copy, D3D12_RESOURCE_STATE_COPY_DEST, COPY_READ),
			create_barrier(source, D3D12_RESOURCE_STATE_COPY_SOURCE, after),
		];
		original(list, back.len() as u32, back.as_ptr());
		before.into_iter().for_each(drop_barrier);
		back.into_iter().for_each(drop_barrier);
	}
	if COPIES.fetch_add(1, Relaxed) == 0 {
		log::line(&format!("depth: first copy of the game's scene depth recorded (it was in state {:#x})", after.0));
	} else if NOTED.load(Relaxed) < 3 && COPIES.load(Relaxed) % 600 == 0 {
		NOTED.fetch_add(1, Relaxed);
		log::line(&format!("depth: {} copies recorded", COPIES.load(Relaxed)));
	}
}

/// Copies the scene depth into ours from wherever the game's barriers last left each plane.
unsafe fn copy_at_frame_end(list: *mut c_void, depth: *mut c_void) {
	let target = COPY.lock().unwrap_or_else(|e| e.into_inner());
	let Some(copy) = target.as_ref() else { return };
	if !PLANES_SEEN.load(Relaxed) {
		return;
	}
	let (state0, state1) = (PLANE_STATE[0].load(Relaxed), PLANE_STATE[1].load(Relaxed));
	unsafe {
		let (Some(_), Some(source)) = (
			ID3D12GraphicsCommandList::from_raw_borrowed(&list),
			ID3D12Resource::from_raw_borrowed(&depth),
		) else { return };
		let commands = ID3D12GraphicsCommandList::from_raw_borrowed(&list).unwrap();
		let original: BarrierFn = transmute(ORIGINAL.load(Relaxed));
		let state = |bits: u32| D3D12_RESOURCE_STATES(bits as i32);
		// One transition for the whole resource when both planes are together, one per plane otherwise.
		let plane = |subresource: u32, before: u32, after: D3D12_RESOURCE_STATES, on: bool| {
			let mut barrier = create_barrier(source, state(before), after);
			if !on {
				let transition: &mut D3D12_RESOURCE_TRANSITION_BARRIER = &mut *barrier.Anonymous.Transition;
				transition.Subresource = subresource;
			}
			barrier
		};
		let together = state0 == state1;
		let to_copy: Vec<D3D12_RESOURCE_BARRIER> = if together {
			vec![plane(ALL_SUBRESOURCES, state0, D3D12_RESOURCE_STATE_COPY_SOURCE, true)]
		} else {
			vec![plane(0, state0, D3D12_RESOURCE_STATE_COPY_SOURCE, false), plane(1, state1, D3D12_RESOURCE_STATE_COPY_SOURCE, false)]
		};
		let mut before: Vec<D3D12_RESOURCE_BARRIER> = to_copy;
		before.push(create_barrier(copy, COPY_READ, D3D12_RESOURCE_STATE_COPY_DEST));
		original(list, before.len() as u32, before.as_ptr());
		commands.CopyResource(copy, source);
		let mut back: Vec<D3D12_RESOURCE_BARRIER> = vec![create_barrier(copy, D3D12_RESOURCE_STATE_COPY_DEST, COPY_READ)];
		if together {
			back.push(plane(ALL_SUBRESOURCES, D3D12_RESOURCE_STATE_COPY_SOURCE.0 as u32, state(state0), true));
		} else {
			back.push(plane(0, D3D12_RESOURCE_STATE_COPY_SOURCE.0 as u32, state(state0), false));
			back.push(plane(1, D3D12_RESOURCE_STATE_COPY_SOURCE.0 as u32, state(state1), false));
		}
		original(list, back.len() as u32, back.as_ptr());
		before.into_iter().for_each(drop_barrier);
		back.into_iter().for_each(drop_barrier);
	}
	if END_COPIES.fetch_add(1, Relaxed) == 0 {
		log::line(&format!("depth: first end-of-frame copy of the scene depth (planes were in states {state0:#x} and {state1:#x})"));
	}
}

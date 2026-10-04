//! Elden Ring reads the keyboard and mouse through DirectInput 8. Its device methods are patched
//! (vtable entries, shared by every device of that kind) so the DLL sees the raw mouse movement
//! and wheel, and so the game gets no input at all while Minecraft has the controls.

use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

use windows_sys::Win32::System::LibraryLoader::{GetModuleHandleW, GetProcAddress, LoadLibraryA};
use windows_sys::Win32::System::Memory::{PAGE_EXECUTE_READWRITE, VirtualProtect};
use windows_sys::core::GUID;

use crate::log;

type HResult = i32;
type GetDeviceState = unsafe extern "system" fn(*mut c_void, u32, *mut c_void) -> HResult;
type GetDeviceData = unsafe extern "system" fn(*mut c_void, u32, *mut u8, *mut u32, u32) -> HResult;
type GetCapabilities = unsafe extern "system" fn(*mut c_void, *mut DiDevCaps) -> HResult;
type CreateDevice = unsafe extern "system" fn(*mut c_void, *const GUID, *mut *mut c_void, *mut c_void) -> HResult;
type Release = unsafe extern "system" fn(*mut c_void) -> u32;
type DirectInput8Create = unsafe extern "system" fn(*mut c_void, u32, *const GUID, *mut *mut c_void, *mut c_void) -> HResult;

const VT_RELEASE: usize = 2;
const VT_CREATE_DEVICE: usize = 3; // IDirectInput8W
const VT_GET_CAPABILITIES: usize = 3; // IDirectInputDevice8W
const VT_GET_DEVICE_STATE: usize = 9;
const VT_GET_DEVICE_DATA: usize = 10;

const IID_IDIRECTINPUT8W: GUID = GUID::from_u128(0xBF798031_483A_4DA2_AA99_5D64ED369700);
const GUID_SYS_MOUSE: GUID = GUID::from_u128(0x6F1D2B60_D5A0_11CF_BFC7_444553540000);
const GUID_SYS_KEYBOARD: GUID = GUID::from_u128(0x6F1D2B61_D5A0_11CF_BFC7_444553540000);

const DI8DEVTYPE_MOUSE: u32 = 0x12;
const DI8DEVTYPE_KEYBOARD: u32 = 0x13;
const DIK_ESCAPE: u32 = 0x01;
const DIMOFS_X: u32 = 0;
const DIMOFS_Y: u32 = 4;
const DIMOFS_Z: u32 = 8;

#[repr(C)]
struct DiDevCaps {
	size: u32,
	flags: u32,
	dev_type: u32,
	rest: [u32; 8],
}

#[derive(Default)]
struct Captured {
	dx: i64,
	dy: i64,
	wheel: i64,
	/// The mouse has been read buffered: then GetDeviceState's totals would count it twice.
	mouse_buffered: bool,
	logged: u8,
}

/// Mouse movement since the last `take`.
pub struct Mouse {
	pub dx: i64,
	pub dy: i64,
	pub wheel: i64,
}

static CAPTURED: Mutex<Captured> = Mutex::new(Captured { dx: 0, dy: 0, wheel: 0, mouse_buffered: false, logged: 0 });
/// True: Elden Ring gets no keyboard or mouse input (Escape still reaches it).
static BLOCK: AtomicBool = AtomicBool::new(false);
static ORIGINAL_STATE: Mutex<Vec<(usize, GetDeviceState)>> = Mutex::new(Vec::new());
static ORIGINAL_DATA: Mutex<Vec<(usize, GetDeviceData)>> = Mutex::new(Vec::new());
static KINDS: Mutex<Option<HashMap<usize, u32>>> = Mutex::new(None);

pub fn set_block(block: bool) {
	BLOCK.store(block, Ordering::Relaxed);
}

pub fn take() -> Mouse {
	let mut c = CAPTURED.lock().unwrap_or_else(|e| e.into_inner());
	let m = Mouse { dx: c.dx, dy: c.dy, wheel: c.wheel };
	(c.dx, c.dy, c.wheel) = (0, 0, 0);
	m
}

fn note(what: &str) {
	let mut c = CAPTURED.lock().unwrap_or_else(|e| e.into_inner());
	if c.logged < 8 {
		c.logged += 1;
		drop(c);
		log::line(&format!("dinput: {what}"));
	}
}

unsafe fn vtable(obj: *mut c_void) -> *mut usize {
	unsafe { *(obj as *mut *mut usize) }
}

/// The device's kind (mouse, keyboard, ...), asked once per device.
unsafe fn kind(device: *mut c_void) -> u32 {
	let mut kinds = KINDS.lock().unwrap_or_else(|e| e.into_inner());
	let kinds = kinds.get_or_insert_with(HashMap::new);
	*kinds.entry(device as usize).or_insert_with(|| unsafe {
		let get: GetCapabilities = std::mem::transmute(*vtable(device).add(VT_GET_CAPABILITIES));
		let mut caps = DiDevCaps { size: size_of::<DiDevCaps>() as u32, flags: 0, dev_type: 0, rest: [0; 8] };
		if get(device, &mut caps) >= 0 { caps.dev_type & 0xFF } else { 0 }
	})
}

fn original<T: Copy>(table: &Mutex<Vec<(usize, T)>>, device: *mut c_void) -> Option<T> {
	let vt = unsafe { vtable(device) } as usize;
	table.lock().unwrap_or_else(|e| e.into_inner()).iter().find(|(v, _)| *v == vt).map(|(_, f)| *f)
}

unsafe extern "system" fn get_device_state(device: *mut c_void, size: u32, data: *mut c_void) -> HResult {
	let Some(orig) = original(&ORIGINAL_STATE, device) else { return -1 };
	let hr = unsafe { orig(device, size, data) };
	if hr < 0 || data.is_null() {
		return hr;
	}
	let block = BLOCK.load(Ordering::Relaxed);
	match unsafe { kind(device) } {
		DI8DEVTYPE_MOUSE if size >= 12 => {
			let s = unsafe { std::slice::from_raw_parts_mut(data as *mut i32, 3) };
			let mut c = CAPTURED.lock().unwrap_or_else(|e| e.into_inner());
			if !c.mouse_buffered {
				c.dx += s[0] as i64;
				c.dy += s[1] as i64;
				c.wheel += s[2] as i64;
			}
			drop(c);
			note("mouse read with GetDeviceState");
			if block {
				unsafe { std::ptr::write_bytes(data as *mut u8, 0, size as usize) };
			}
		}
		DI8DEVTYPE_KEYBOARD if size >= 256 => {
			note("keyboard read with GetDeviceState");
			if block {
				let keys = unsafe { std::slice::from_raw_parts_mut(data as *mut u8, size as usize) };
				let escape = keys[DIK_ESCAPE as usize];
				keys.fill(0);
				keys[DIK_ESCAPE as usize] = escape;
			}
		}
		_ => {}
	}
	hr
}

unsafe extern "system" fn get_device_data(device: *mut c_void, stride: u32, data: *mut u8, count: *mut u32, flags: u32) -> HResult {
	let Some(orig) = original(&ORIGINAL_DATA, device) else { return -1 };
	let hr = unsafe { orig(device, stride, data, count, flags) };
	if hr < 0 || data.is_null() || count.is_null() || stride < 8 {
		return hr;
	}
	let n = unsafe { *count } as usize;
	let block = BLOCK.load(Ordering::Relaxed);
	let kind = unsafe { kind(device) };
	let entry = |i: usize| unsafe { data.add(i * stride as usize) };
	let ofs = |i: usize| unsafe { (entry(i) as *const u32).read_unaligned() };
	let value = |i: usize| unsafe { (entry(i).add(4) as *const u32).read_unaligned() };
	if kind == DI8DEVTYPE_MOUSE {
		note("mouse read with GetDeviceData");
		let mut c = CAPTURED.lock().unwrap_or_else(|e| e.into_inner());
		c.mouse_buffered = true;
		for i in 0..n {
			let v = value(i) as i32 as i64;
			match ofs(i) {
				DIMOFS_X => c.dx += v,
				DIMOFS_Y => c.dy += v,
				DIMOFS_Z => c.wheel += v,
				_ => {}
			}
		}
	} else if kind == DI8DEVTYPE_KEYBOARD {
		note("keyboard read with GetDeviceData");
	}
	if block && (kind == DI8DEVTYPE_MOUSE || kind == DI8DEVTYPE_KEYBOARD) {
		// Keep only Escape, so Elden Ring's own menu still opens.
		let mut kept = 0;
		for i in 0..n {
			if kind == DI8DEVTYPE_KEYBOARD && ofs(i) == DIK_ESCAPE {
				if kept != i {
					unsafe { std::ptr::copy(entry(i), entry(kept), stride as usize) };
				}
				kept += 1;
			}
		}
		unsafe { *count = kept as u32 };
	}
	hr
}

unsafe fn patch(slot: *mut usize, with: usize) -> usize {
	unsafe {
		let mut old = 0;
		VirtualProtect(slot as *const c_void, size_of::<usize>(), PAGE_EXECUTE_READWRITE, &mut old);
		let before = slot.read();
		slot.write(with);
		VirtualProtect(slot as *const c_void, size_of::<usize>(), old, &mut old);
		before
	}
}

/// Patches the keyboard and mouse device methods. Safe to call once, from any thread.
pub fn install() {
	unsafe {
		let mut module = GetModuleHandleW([b'd' as u16, b'i' as u16, b'n' as u16, b'p' as u16, b'u' as u16, b't' as u16, b'8' as u16, 0].as_ptr());
		if module.is_null() {
			module = LoadLibraryA(c"dinput8.dll".as_ptr().cast());
		}
		let Some(create) = GetProcAddress(module, c"DirectInput8Create".as_ptr().cast()) else {
			log::line("dinput: DirectInput8Create not found; Elden Ring will see every key");
			return;
		};
		let create: DirectInput8Create = std::mem::transmute(create);
		let instance = GetModuleHandleW(std::ptr::null());
		let mut di: *mut c_void = std::ptr::null_mut();
		if create(instance, 0x0800, &IID_IDIRECTINPUT8W, &mut di, std::ptr::null_mut()) < 0 || di.is_null() {
			log::line("dinput: couldn't create DirectInput; Elden Ring will see every key");
			return;
		}
		let create_device: CreateDevice = std::mem::transmute(*vtable(di).add(VT_CREATE_DEVICE));
		for (name, guid) in [("mouse", GUID_SYS_MOUSE), ("keyboard", GUID_SYS_KEYBOARD)] {
			let mut device: *mut c_void = std::ptr::null_mut();
			if create_device(di, &guid, &mut device, std::ptr::null_mut()) < 0 || device.is_null() {
				log::line(&format!("dinput: no {name} device"));
				continue;
			}
			let vt = vtable(device);
			let mut states = ORIGINAL_STATE.lock().unwrap_or_else(|e| e.into_inner());
			if !states.iter().any(|(v, _)| *v == vt as usize) {
				let before = patch(vt.add(VT_GET_DEVICE_STATE), get_device_state as GetDeviceState as usize);
				states.push((vt as usize, std::mem::transmute(before)));
				let before = patch(vt.add(VT_GET_DEVICE_DATA), get_device_data as GetDeviceData as usize);
				ORIGINAL_DATA.lock().unwrap_or_else(|e| e.into_inner()).push((vt as usize, std::mem::transmute(before)));
				log::line(&format!("dinput: hooked the {name} device methods"));
			}
			drop(states);
			let release: Release = std::mem::transmute(*vtable(device).add(VT_RELEASE));
			release(device);
		}
		// The DirectInput object is kept: releasing it could unload what the vtables live in.
	}
}

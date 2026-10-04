//! Edge-triggered keys, read only while Elden Ring's window has focus.

use windows_sys::Win32::UI::Input::KeyboardAndMouse::GetAsyncKeyState;
use windows_sys::Win32::UI::WindowsAndMessaging::{CURSOR_SHOWING, CURSORINFO, GetCursorInfo, GetForegroundWindow, GetWindowThreadProcessId};
use windows_sys::Win32::System::Threading::GetCurrentProcessId;

/// R: Elden Ring's Event Action while Minecraft has the controls (unbound in vanilla Minecraft).
pub const VK_R: u8 = 0x52;
pub const VK_F6: u8 = 0x75;
pub const VK_F7: u8 = 0x76;
pub const VK_F8: u8 = 0x77;
pub const VK_F9: u8 = 0x78;
pub const VK_F10: u8 = 0x79;
pub const VK_F11: u8 = 0x7A;

pub struct Keys {
	down: [bool; 256],
}

impl Default for Keys {
	fn default() -> Self {
		Self { down: [false; 256] }
	}
}

impl Keys {
	/// True once per press, and only while the game is the foreground window.
	pub fn pressed(&mut self, vk: u8) -> bool {
		let now = focused() && unsafe { GetAsyncKeyState(vk as i32) } as u16 & 0x8000 != 0;
		let was = std::mem::replace(&mut self.down[vk as usize], now);
		now && !was
	}
}

pub fn focused() -> bool {
	unsafe {
		let mut pid = 0;
		GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
		pid == GetCurrentProcessId()
	}
}

/// The system mouse cursor is showing: Elden Ring shows it in its menus.
pub fn cursor_visible() -> bool {
	unsafe {
		let mut info: CURSORINFO = std::mem::zeroed();
		info.cbSize = size_of::<CURSORINFO>() as u32;
		GetCursorInfo(&mut info) != 0 && info.flags & CURSOR_SHOWING != 0
	}
}

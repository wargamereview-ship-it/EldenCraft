//! The keyboard and mouse, forwarded to Minecraft while it has the controls.
//!
//! Keys are polled once per frame while Elden Ring has focus. With a Minecraft screen open
//! (inventory, chat, ...), typed characters are sent as text and Escape closes the screen;
//! without one, Escape stays with Elden Ring (its own menu).

use windows_sys::Win32::UI::Input::KeyboardAndMouse::{GetAsyncKeyState, GetKeyState, MAPVK_VK_TO_VSC, MapVirtualKeyW, ToUnicode};

use crate::link::Link;
use crate::proto;

const VK_ESCAPE: u8 = 0x1B;

/// (Windows virtual key, SDL scancode) for keys, (virtual key, SDL button) for mouse buttons.
const KEYS: &[(u8, u16)] = &[
	(b'A', 4), (b'B', 5), (b'C', 6), (b'D', 7), (b'E', 8), (b'F', 9), (b'G', 10), (b'H', 11), (b'I', 12),
	(b'J', 13), (b'K', 14), (b'L', 15), (b'M', 16), (b'N', 17), (b'O', 18), (b'P', 19), (b'Q', 20), (b'R', 21),
	(b'S', 22), (b'T', 23), (b'U', 24), (b'V', 25), (b'W', 26), (b'X', 27), (b'Y', 28), (b'Z', 29),
	(b'1', 30), (b'2', 31), (b'3', 32), (b'4', 33), (b'5', 34), (b'6', 35), (b'7', 36), (b'8', 37), (b'9', 38), (b'0', 39),
	(0x0D, 40),  // enter
	(VK_ESCAPE, 41),
	(0x08, 42),  // backspace
	(0x09, 43),  // tab
	(0x20, 44),  // space
	(0xBD, 45),  // -
	(0xBB, 46),  // =
	(0xDB, 47),  // [
	(0xDD, 48),  // ]
	(0xDC, 49),  // backslash
	(0xBA, 51),  // ;
	(0xDE, 52),  // '
	(0xC0, 53),  // `
	(0xBC, 54),  // ,
	(0xBE, 55),  // .
	(0xBF, 56),  // /
	(0x74, 62),  // F5: Minecraft's camera
	(0x2E, 76),  // delete
	(0x27, 79),  // right
	(0x25, 80),  // left
	(0x28, 81),  // down
	(0x26, 82),  // up
	(0xA0, 225), // left shift
	(0xA2, 224), // left control
	(0xA4, 226), // left alt
];
const BUTTONS: &[(u8, u16)] = &[
	(0x01, 1), // left
	(0x02, 3), // right
	(0x04, 2), // middle
];

#[derive(Default)]
pub struct Input {
	held: Vec<(u16, u16)>, // (event kind, code) we told Minecraft is down
	/// Mouse cursor over a Minecraft screen, in its window's pixels.
	cursor: Option<(f64, f64)>,
}

impl Input {
	/// Sends Minecraft every press and release since the last frame. `active` false lets go of
	/// everything (focus lost, Elden Ring has control). `screen` is the size of Minecraft's
	/// window when one of its screens is open.
	pub fn poll(&mut self, link: &Link, active: bool, screen: Option<(u32, u32)>, mouse: (i64, i64)) {
		if !active {
			if !self.held.is_empty() {
				self.held.clear();
				link.send_input(proto::IN_RELEASE_ALL, 0, 0);
			}
			self.cursor = None;
			return;
		}
		let down = |vk: u8| unsafe { GetAsyncKeyState(vk as i32) } as u16 & 0x8000 != 0;
		for (kind, table) in [(proto::IN_KEY, KEYS), (proto::IN_MOUSE_BUTTON, BUTTONS)] {
			for &(vk, code) in table {
				// Escape opens Elden Ring's menu unless a Minecraft screen is there to close.
				let now = down(vk) && (vk != VK_ESCAPE || screen.is_some());
				let was = self.held.contains(&(kind, code));
				if now == was {
					continue;
				}
				link.send_input(kind, code, now as i32);
				if now {
					self.held.push((kind, code));
					if kind == proto::IN_KEY && screen.is_some() {
						if let Some(c) = typed(vk) {
							link.send_input(proto::IN_TEXT, 0, c as i32);
						}
					}
				} else {
					self.held.retain(|h| *h != (kind, code));
				}
			}
		}

		// A screen is open: the mouse moves a cursor over it instead of turning the view.
		match screen {
			Some((w, h)) => {
				let (x, y) = self.cursor.get_or_insert((w as f64 / 2.0, h as f64 / 2.0));
				*x = (*x + mouse.0 as f64).clamp(0.0, w as f64 - 1.0);
				*y = (*y + mouse.1 as f64).clamp(0.0, h as f64 - 1.0);
				link.send_input_xy(proto::IN_CURSOR, *x as i32, *y as i32);
			}
			None => self.cursor = None,
		}
	}

	/// Where the cursor is over Minecraft's screen, if one is open.
	pub fn cursor(&self) -> Option<(f64, f64)> {
		self.cursor
	}
}

/// The character a key types with the current shift and caps lock state, if any.
fn typed(vk: u8) -> Option<char> {
	unsafe {
		let mut state = [0u8; 256];
		for k in [0x10u8, 0xA0, 0xA1] {
			if GetAsyncKeyState(k as i32) as u16 & 0x8000 != 0 {
				state[k as usize] = 0x80;
			}
		}
		if GetKeyState(0x14) & 1 != 0 {
			state[0x14] = 0x01; // caps lock on
		}
		if GetAsyncKeyState(0x11) as u16 & 0x8000 != 0 {
			return None; // ctrl+key is a shortcut, not text
		}
		let scan = MapVirtualKeyW(vk as u32, MAPVK_VK_TO_VSC);
		let mut out = [0u16; 4];
		let n = ToUnicode(vk as u32, scan, state.as_ptr(), out.as_mut_ptr(), out.len() as i32, 0);
		(n == 1).then(|| char::from_u32(out[0] as u32)).flatten().filter(|c| !c.is_control())
	}
}

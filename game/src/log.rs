//! `eldencraft.log` next to `eldencraft.dll`, one line per call.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::windows::ffi::OsStringExt;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use windows_sys::Win32::System::LibraryLoader::GetModuleFileNameW;

static FILE: Mutex<Option<File>> = Mutex::new(None);
static DIR: std::sync::OnceLock<PathBuf> = std::sync::OnceLock::new();

/// A file beside this DLL (the working directory, if the DLL's path can't be read).
pub fn beside_dll(name: &str) -> PathBuf {
	DIR.get().map_or_else(|| PathBuf::from(name), |dir| dir.join(name))
}

/// Opens the log beside this DLL (the working directory, if the DLL's path can't be read).
pub fn init(hmodule: usize) {
	let mut buf = [0u16; 1024];
	let len = unsafe { GetModuleFileNameW(hmodule as _, buf.as_mut_ptr(), buf.len() as u32) } as usize;
	if len > 0 {
		if let Some(dir) = PathBuf::from(std::ffi::OsString::from_wide(&buf[..len])).parent() {
			let _ = DIR.set(dir.to_path_buf());
		}
	}
	let path = beside_dll("eldencraft.log");
	if let Ok(mut guard) = FILE.lock() {
		*guard = OpenOptions::new().create(true).write(true).truncate(true).open(path).ok();
	}
}

pub fn line(text: &str) {
	let Ok(mut guard) = FILE.lock() else {
		return;
	};
	if let Some(file) = guard.as_mut() {
		let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64()).unwrap_or(0.0);
		let _ = writeln!(file, "[{secs:.3}] {text}");
		let _ = file.flush();
	}
}

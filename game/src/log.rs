//! `eldencraft.log` next to `eldencraft.dll`, one line per call.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::windows::ffi::OsStringExt;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use windows_sys::Win32::System::LibraryLoader::GetModuleFileNameW;

static FILE: Mutex<Option<File>> = Mutex::new(None);

/// Opens the log beside this DLL (the working directory, if the DLL's path can't be read).
pub fn init(hmodule: usize) {
	let mut buf = [0u16; 1024];
	let len = unsafe { GetModuleFileNameW(hmodule as _, buf.as_mut_ptr(), buf.len() as u32) } as usize;
	let path = match len {
		0 => PathBuf::from("eldencraft.log"),
		_ => PathBuf::from(std::ffi::OsString::from_wide(&buf[..len])).with_file_name("eldencraft.log"),
	};
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

//! Starts Minecraft from inside the game, so both share one Wine session under Proton (shared
//! memory names are only visible within it). Uses the portable Prism Launcher at
//! `%LOCALAPPDATA%\EldenCraft\Prism` and its `EldenCraft` instance.

use std::path::PathBuf;
use std::process::Command;

use windows_sys::Win32::Foundation::{CloseHandle, INVALID_HANDLE_VALUE};
use windows_sys::Win32::System::Diagnostics::ToolHelp::{
	CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW, TH32CS_SNAPPROCESS,
};
use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_TERMINATE, TerminateProcess};

use crate::log;

pub fn start_minecraft() {
	let Some(local) = std::env::var_os("LOCALAPPDATA") else {
		log::line("launcher: LOCALAPPDATA is not set; start Minecraft yourself");
		return;
	};
	let dir = PathBuf::from(local).join("EldenCraft");
	let prism = dir.join("Prism").join("prismlauncher.exe");
	if !prism.exists() {
		log::line(&format!("launcher: {} not found; start Minecraft yourself", prism.display()));
		return;
	}
	if let Some(name) = running(&["prismlauncher.exe", "javaw.exe"]) {
		log::line(&format!("launcher: {name} is already running; not starting another Minecraft"));
		return;
	}
	match Command::new(&prism).args(["--launch", "EldenCraft"]).current_dir(&dir).spawn() {
		Ok(child) => log::line(&format!("launcher: started Prism (pid {})", child.id())),
		Err(e) => log::line(&format!("launcher: couldn't start {}: {e}", prism.display())),
	}
}

/// Prism sometimes hangs while starting under Wine and never launches Minecraft. Ends every
/// Prism and Minecraft process in this session and starts Prism again.
pub fn restart_minecraft() {
	let mut ended = Vec::new();
	for pid in pids(&["prismlauncher.exe", "javaw.exe"]) {
		unsafe {
			let h = OpenProcess(PROCESS_TERMINATE, 0, pid);
			if !h.is_null() {
				if TerminateProcess(h, 1) != 0 {
					ended.push(pid);
				}
				CloseHandle(h);
			}
		}
	}
	log::line(&format!("launcher: Minecraft never connected; ended {ended:?} and starting Prism again"));
	std::thread::sleep(std::time::Duration::from_secs(2));
	start_minecraft();
}

fn pids(names: &[&str]) -> Vec<u32> {
	let mut out = Vec::new();
	unsafe {
		let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
		if snapshot == INVALID_HANDLE_VALUE {
			return out;
		}
		let mut entry: PROCESSENTRY32W = std::mem::zeroed();
		entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;
		let mut ok = Process32FirstW(snapshot, &mut entry) != 0;
		while ok {
			let len = entry.szExeFile.iter().position(|&c| c == 0).unwrap_or(entry.szExeFile.len());
			let exe = String::from_utf16_lossy(&entry.szExeFile[..len]);
			if names.iter().any(|n| exe.eq_ignore_ascii_case(n)) {
				out.push(entry.th32ProcessID);
			}
			ok = Process32NextW(snapshot, &mut entry) != 0;
		}
		CloseHandle(snapshot);
	}
	out
}

/// The first of `names` (case-insensitive) that a running process has.
fn running(names: &[&str]) -> Option<String> {
	unsafe {
		let snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
		if snapshot == INVALID_HANDLE_VALUE {
			return None;
		}
		let mut entry: PROCESSENTRY32W = std::mem::zeroed();
		entry.dwSize = size_of::<PROCESSENTRY32W>() as u32;
		let mut found = None;
		let mut ok = Process32FirstW(snapshot, &mut entry) != 0;
		while ok && found.is_none() {
			let len = entry.szExeFile.iter().position(|&c| c == 0).unwrap_or(entry.szExeFile.len());
			let exe = String::from_utf16_lossy(&entry.szExeFile[..len]);
			found = names.iter().find(|n| exe.eq_ignore_ascii_case(n)).map(|_| exe);
			ok = Process32NextW(snapshot, &mut entry) != 0;
		}
		CloseHandle(snapshot);
		found
	}
}

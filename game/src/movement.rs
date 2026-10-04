//! Queue a position through Elden Ring's own character controller.
//!
//! Verified against the installed game's code: ChrCtrl's position-sync bit consumes the
//! vector immediately after `chr_proxy_flags` (ChrCtrl + 0x100), NOT physics.position.
//! ChrCtrl's update passes that vector to the physics setter, which updates position and
//! last_update_position, requests the Havok proxy update, and flushes it into both proxies.
//! Setting the bit without filling this vector was the earlier teleport toward the origin.
//! Writing physics.position alone lets the unchanged proxy put the character back.

use std::mem::{offset_of, size_of};
use std::ptr::addr_of_mut;

use eldenring::cs::{ChrCtrl, ChrCtrlChrProxyFlags, PlayerIns};
use eldenring::position::HavokPosition;

use crate::world::{Space, V3};

// The destination vector is private (`unk100`) in the pinned fromsoftware-rs layout.
// Derive its address from the preceding public field and fail compilation if that layout
// changes. No function address or executable patch is needed.
const DESTINATION: usize = offset_of!(ChrCtrl, chr_proxy_flags) + size_of::<ChrCtrlChrProxyFlags>();
const _: () = assert!(DESTINATION == 0x100);
const _: () = assert!(size_of::<HavokPosition>() == 16);

pub enum Request {
	Queued,
	Pending,
	Invalid,
}

/// Called after physics. The game consumes this request in its next character update.
pub fn request(player: &mut PlayerIns, space: &Space, pos: V3) -> Request {
	let h = space.mc_to_havok(pos);
	if !pos.iter().all(|v| v.is_finite()) || !h.iter().all(|v| v.is_finite()) {
		return Request::Invalid;
	}
	// Preserve the game's fourth vector component and let its setter handle previous position
	// and proxy bookkeeping. Only the pending destination is changed here.
	let target = HavokPosition(h[0], h[1], h[2], player.chr_ins.modules.physics.position.3);
	let ctrl = player.chr_ins.chr_ctrl.as_mut();
	if ctrl.chr_proxy_flags.position_sync_requested() {
		// Preserve any outstanding request, including one queued by grace travel or a script.
		return Request::Pending;
	}
	unsafe {
		addr_of_mut!(ctrl.chr_proxy_flags)
			.byte_add(size_of::<ChrCtrlChrProxyFlags>())
			.cast::<HavokPosition>()
			.write_unaligned(target);
	}
	ctrl.chr_proxy_flags.set_position_sync_requested(true);
	Request::Queued
}

/// Cancel an unconsumed request of ours when handing control back. Preserve game requests.
pub fn cancel(player: &mut PlayerIns, space: &Space, expected: Option<V3>) {
	let Some(expected) = expected else { return };
	let h = space.mc_to_havok(expected);
	let ctrl = player.chr_ins.chr_ctrl.as_mut();
	if !ctrl.chr_proxy_flags.position_sync_requested() {
		return;
	}
	let queued = unsafe {
		addr_of_mut!(ctrl.chr_proxy_flags)
			.byte_add(size_of::<ChrCtrlChrProxyFlags>())
			.cast::<HavokPosition>()
			.read_unaligned()
	};
	if [queued.0 - h[0], queued.1 - h[1], queued.2 - h[2]].iter().all(|v| v.abs() < 0.001) {
		ctrl.chr_proxy_flags.set_position_sync_requested(false);
	}
}

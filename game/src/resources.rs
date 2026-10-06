//! Read-only grace observer. Flag 9000 brackets the native grace animation/menu in t000001000.
use eldenring::cs::CSEventFlagMan;
use fromsoftware_shared::FromStatic;
use crate::{grace_edges::GraceEdges, link::Link, log, proto};
#[derive(Default)]
pub struct Resources { edges: GraceEdges, pending: u32, arrived: bool, here: Option<[f64; 3]> }
impl Resources {
    /// Where the player is (Minecraft coordinates); sent with grace events so nodes keep clear of graces.
    pub fn at(&mut self, pos: [f64; 3]) { self.here = Some(pos); }
    /// The player has just appeared after a load: the game puts them at their last grace.
    pub fn arrived(&mut self) { self.arrived = true; }
    fn event(code: u16, pos: [f64; 3]) -> proto::InputEvent {
        proto::InputEvent { kind: proto::IN_GRACE_REST, code, a: pos[0].floor() as i32, b: pos[1].floor() as i32, c: pos[2].floor() as i32 }
    }
    pub fn frame(&mut self, link: &Link) {
        let Ok(manager) = (unsafe { CSEventFlagMan::instance() }) else { return; };
        let flags = &manager.virtual_memory_flag;
        if flags.event_flag_divisor != 1000 || flags.event_flag_holder_size != 125 || flags.flag_blocks.is_null() { return; }
        if self.edges.observe(flags.get_flag(9000)) {
            self.pending = self.pending.saturating_add(1);
            log::line("resources: native grace rest observed; gathering nodes replenish");
        }
        let here = self.here.unwrap_or([0.0; 3]);
        while link.mc_connected() && self.pending > 0 {
            if !link.send_inputs(&[Self::event(0, here)]) { break; }
            self.pending -= 1;
        }
        if self.arrived && self.here.is_some() && link.mc_connected() && link.send_inputs(&[Self::event(1, here)]) {
            self.arrived = false;
            log::line(&format!("resources: arrived at ({:.1}, {:.1}, {:.1}), the last grace; gathering nodes keep clear", here[0], here[1], here[2]));
        }
    }
}

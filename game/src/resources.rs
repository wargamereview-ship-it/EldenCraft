//! Read-only grace observer. Flag 9000 brackets the native grace animation/menu in t000001000.
use eldenring::cs::CSEventFlagMan;
use fromsoftware_shared::FromStatic;
use crate::{grace_edges::GraceEdges, link::Link, log, proto};
#[derive(Default)]
pub struct Resources { edges: GraceEdges, pending: u32 }
impl Resources {
    pub fn frame(&mut self, link: &Link) {
        let Ok(manager) = (unsafe { CSEventFlagMan::instance() }) else { return; };
        let flags = &manager.virtual_memory_flag;
        if flags.event_flag_divisor != 1000 || flags.event_flag_holder_size != 125 || flags.flag_blocks.is_null() { return; }
        if self.edges.observe(flags.get_flag(9000)) {
            self.pending = self.pending.saturating_add(1);
            log::line("resources: native grace rest observed; gathering nodes replenish");
        }
        while link.mc_connected() && self.pending > 0 {
            if !link.send_inputs(&[proto::InputEvent { kind: proto::IN_GRACE_REST, code: 0, a: 0, b: 0, c: 0 }]) { break; }
            self.pending -= 1;
        }
    }
}

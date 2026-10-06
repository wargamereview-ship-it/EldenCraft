//! Rest edges are distinct from loading, interacting, or attaching while already seated.
#[derive(Default)]
pub struct GraceEdges { last: Option<bool> }
impl GraceEdges {
    pub fn observe(&mut self, resting: bool) -> bool {
        let edge = self.last == Some(false) && resting;
        self.last = Some(resting);
        edge
    }
}

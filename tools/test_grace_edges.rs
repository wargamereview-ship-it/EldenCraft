#[path="../game/src/grace_edges.rs"] mod grace;
#[test] fn attaching_at_grace_does_not_refresh() {
    let mut state=grace::GraceEdges::default();
    assert!(!state.observe(true));assert!(!state.observe(true));
    assert!(!state.observe(false));assert!(state.observe(true));
}
#[test] fn one_replenishment_per_rest_not_per_frame() {
    let mut state=grace::GraceEdges::default();
    assert!(!state.observe(false));assert!(state.observe(true));
    for _ in 0..600 {assert!(!state.observe(true));}
    assert!(!state.observe(false));assert!(state.observe(true));
}

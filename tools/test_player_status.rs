#[allow(dead_code)]
#[path = "../game/src/player_status.rs"] mod player_status;
#[allow(dead_code)]
#[path = "../game/src/proto.rs"] mod proto;
use player_status::*;

#[test] fn all_seven_gauges_and_timers_keep_the_native_order() {
    let gauges = [10, 20, 30, 40, 50, 60, 70];
    let timers = [12.0, 13.0, 0.25, 999.0, 30.0, 2.0, 3.0];
    let durations = [90.0, 180.0, 1.0, 1000.0, 30.0, 3.0, 4.0];
    let s = snapshot(gauges, [100; 7], timers, durations);
    assert_eq!(s.buildup, [90, 80, 70, 60, 50, 40, 30]);
    assert_eq!(s.maximum, [100; 7]);
    assert_eq!(s.remaining, [12.0, 13.0, 0.25, -1.0, 30.0, 2.0, 3.0]);
    assert_eq!(s.duration[3], 0.0);
}
#[test] fn inactive_native_sentinels_and_invalid_values_never_become_effects() {
    let s = snapshot([u32::MAX, 200, 3, 0, 0, 0, 0], [100, 100, 0, 0, 0, 0, 0],
        [-1.0, 0.0, f32::NAN, f32::INFINITY, 36_001.0, -5.0, 2.0], [f32::NAN; 7]);
    assert_eq!(s.buildup, [0, 0, 0, 0, 0, 0, 0]);
    assert_eq!(s.remaining, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 2.0]);
    assert_eq!(s.duration, [0.0; 7]);
}
#[test] fn native_cure_clears_a_running_timer_without_guessing_from_gauges() {
    let active = snapshot([0; 7], [100; 7], [10.0; 7], [30.0; 7]);
    assert_eq!(active.remaining[4], 10.0);
    let clear = snapshot([0; 7], [100; 7], [-1.0; 7], [-1.0; 7]);
    assert_eq!(clear.remaining, [0.0; 7]);
}
#[test] fn a_full_resistance_gauge_means_no_buildup() {
    let s = snapshot([100, 60, 0, 0, 0, 0, 0], [100; 7], [0.0; 7], [0.0; 7]);
    assert_eq!(s.buildup, [0, 40, 100, 100, 100, 100, 100]);
}
#[test] fn protocol_layout_fits_reserved_space_and_matches_java_offsets() {
    use std::mem::{offset_of, size_of};
    assert_eq!(size_of::<proto::PlayerStatuses>(), 136);
    assert_eq!(offset_of!(proto::PlayerStatuses, buildup), 24);
    assert_eq!(offset_of!(proto::PlayerStatuses, maximum), 52);
    assert_eq!(offset_of!(proto::PlayerStatuses, remaining), 80);
    assert_eq!(offset_of!(proto::PlayerStatuses, duration), 108);
    assert!(proto::OFF_PLAYER_STATUSES + size_of::<proto::PlayerStatuses>() <= proto::OFF_INPUT_RING);
}

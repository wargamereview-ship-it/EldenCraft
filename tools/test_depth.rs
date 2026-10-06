#[path="../game/src/depth_math.rs"] mod depth;
use depth::*;
const N: f32 = 0.1;
const F: f32 = 5000.0;
#[test] fn encodings_round_trip() {
    for mode in [STANDARD, REVERSED, REVERSED_INFINITE] {
        for dist in [0.5f32, 2.0, 7.0, 25.0, 100.0, 800.0] {
            let back = distance(mode, encode(mode, dist, N, F), N, F);
            assert!((back - dist).abs() / dist < 1e-3, "mode {mode} dist {dist}: {back}");
        }
    }
}
#[test] fn sky_is_far_and_the_ground_near_in_each_encoding() {
    assert!(distance(STANDARD, 1.0, N, F) > 4000.0);
    assert!(distance(REVERSED, 0.0, N, F) > 4000.0);
    assert!(distance(REVERSED_INFINITE, 0.0, N, F) > 1e5);
}
#[test] fn the_right_encoding_is_found_from_one_known_distance() {
    for truth in [3.0f32, 6.5, 12.0, 40.0, 150.0] {
        assert_eq!(calibrate(encode(STANDARD, truth, N, F), truth, N, F), Some(STANDARD), "standard {truth}");
        let rev = calibrate(encode(REVERSED, truth, N, F), truth, N, F);
        assert!(rev == Some(REVERSED) || rev == Some(REVERSED_INFINITE), "reversed {truth}: {rev:?}");
        let inf = calibrate(encode(REVERSED_INFINITE, truth, N, F), truth, N, F);
        assert!(inf == Some(REVERSED) || inf == Some(REVERSED_INFINITE), "infinite {truth}: {inf:?}");
    }
}
#[test] fn a_value_that_fits_nothing_or_nonsense_is_rejected() {
    // The sky, when the ground was expected.
    assert_eq!(calibrate(1.0, 6.0, N, F), None);
    assert_eq!(calibrate(0.0, 6.0, N, F), None);
    assert_eq!(calibrate(f32::NAN, 6.0, N, F), None);
    assert_eq!(calibrate(0.5, 0.05, N, F), None);
    assert_eq!(calibrate(1.5, 6.0, N, F), None);
}

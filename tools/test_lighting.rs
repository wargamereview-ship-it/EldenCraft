#[path="../game/src/lighting.rs"] mod lighting;
use lighting::light;
fn brightness(c: [f32; 3]) -> f32 { (c[0] + c[1] + c[2]) / 3.0 }
#[test] fn noon_is_bright_with_the_sun_overhead() {
    let l = light(12.0, true);
    assert!(l.dir[1] > 0.9);
    assert!(brightness(l.direct) > 0.6 && brightness(l.ambient) > 0.5);
}
#[test] fn midnight_is_dim_and_blue_with_the_moon_above() {
    let l = light(0.0, true);
    assert!(l.dir[1] > 0.9, "the moon is up at midnight: {:?}", l.dir);
    assert!(brightness(l.ambient) < 0.2 && l.ambient[2] > l.ambient[0]);
    assert!(brightness(l.direct) < 0.25);
}
#[test] fn the_low_sun_is_warm() {
    let l = light(7.0, true);
    assert!(l.direct[0] > l.direct[2] * 1.4, "{:?}", l.direct);
    assert!(l.dir[0] > 0.5, "morning sun in the east: {:?}", l.dir);
    assert!(light(17.0, true).dir[0] < -0.5);
}
#[test] fn indoors_has_no_direct_light_at_any_hour() {
    for h in 0..24 { let l = light(h as f32, false); assert_eq!(l.direct, [0.0; 3]); assert!(brightness(l.ambient) > 0.2); }
}
#[test] fn the_day_changes_smoothly() {
    let mut last = light(0.0, true);
    for step in 1..=2400 {
        let l = light(step as f32 / 100.0, true);
        for i in 0..3 { assert!((l.ambient[i] - last.ambient[i]).abs() < 0.02); assert!((l.direct[i] - last.direct[i]).abs() < 0.05); }
        last = l;
    }
}
#[test] fn approaching_a_target_converges() {
    let mut l = light(12.0, true);
    let target = light(12.0, false);
    for _ in 0..200 { l.approach(target, 0.1); }
    for i in 0..3 { assert!((l.ambient[i] - target.ambient[i]).abs() < 1e-3 && l.direct[i].abs() < 1e-3); }
}

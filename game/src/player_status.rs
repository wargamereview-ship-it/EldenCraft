//! Read-only mirror of the local player's native status HUD. Damage stays in ER's HP sensor.
//! ER 2.7.1's 0x659e10 fills PlayerGameData's arrays using SpEffect categories
//! 2, 5, 6, death's 116/117, 260, 436, 437. Inactive timers are -1, not zero.
//! Deathblight uses a 999/1000 sentinel while active, rather than a real duration.

pub const NAMES: [&str; 7] = ["poison", "scarlet rot", "blood loss", "deathblight", "frostbite", "sleep", "madness"];

#[derive(Clone, Copy, Default)]
pub struct Statuses {
    pub buildup: [u32; 7], pub maximum: [u32; 7],
    pub remaining: [f32; 7], pub duration: [f32; 7],
}

/// Reject corrupt gauges independently, and normalize ER's inactive/death sentinels.
pub fn snapshot(buildup: [u32; 7], maximum: [u32; 7], timers: [f32; 7], duration: [f32; 7]) -> Statuses {
    let mut out = Statuses::default();
    for i in 0..7 {
        if maximum[i] > 0 && maximum[i] <= 100_000 && buildup[i] <= 100_000 {
            out.maximum[i] = maximum[i];
            out.buildup[i] = buildup[i].min(maximum[i]);
        }
        if timers[i].is_finite() && timers[i] > 0.0 && timers[i] <= 36_000.0 {
            if i == 3 && timers[i] == 999.0 && duration[i] == 1000.0 {
                out.remaining[i] = -1.0;
            } else {
                out.remaining[i] = timers[i];
                if duration[i].is_finite() && duration[i] > 0.0 && duration[i] <= 36_000.0 {
                    out.duration[i] = duration[i].max(timers[i]);
                }
            }
        }
    }
    out
}

#[derive(Default)]
pub struct Monitor {
    active: u8,
    last_log: Option<std::time::Instant>,
}
impl Monitor {
    pub fn observe(&mut self, statuses: &Statuses) -> Option<String> {
        let active = (0..7).fold(0, |bits, i| bits | if statuses.remaining[i] != 0.0 { 1 << i } else { 0 });
        let changed = active != self.active;
        self.active = active;
        let has_status = active != 0 || statuses.buildup.iter().any(|&v| v > 0);
        if !changed && (!has_status || self.last_log.is_some_and(|t| t.elapsed().as_secs_f32() < 1.0)) { return None; }
        self.last_log = Some(std::time::Instant::now());
        let parts: Vec<String> = (0..7).filter(|&i| statuses.buildup[i] > 0 || statuses.remaining[i] != 0.0)
            .map(|i| format!("{} {}/{} remaining {:.2}s / {:.2}s", NAMES[i], statuses.buildup[i], statuses.maximum[i], statuses.remaining[i], statuses.duration[i])).collect();
        Some(format!("life: player statuses: {}", if parts.is_empty() { "clear".to_string() } else { parts.join(", ") }))
    }
}

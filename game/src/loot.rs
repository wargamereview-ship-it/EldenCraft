//! Read-only boss completion observer. Rewards follow ER's defeat/surrender flags, not phase HP.
use std::collections::{HashMap, HashSet};
use std::ptr::NonNull;
use std::time::{Duration, Instant};
use eldenring::cs::{CSEventFlagMan, ChrIns, ChrInsExt, PlayerIns};
use fromsoftware_shared::FromStatic;
use crate::{loot_catalog::BOSSES, log, proto, world::Space};

pub type Reward = [proto::InputEvent; 4];
/// The runes Elden Ring pays for killing an enemy (its NpcParam row's `get_soul`), or 0 if the row is not loaded.
pub fn npc_runes(npc: i32) -> u32 {
    use eldenring::cs::{NpcParam, SoloParamRepository};
    let Ok(repo) = (unsafe { SoloParamRepository::instance_mut() }) else { return 0 };
    u32::try_from(npc).ok().and_then(|id| repo.get::<NpcParam>(id)).map_or(0, |row| row.get_soul())
}
pub fn batch(pos: [f64; 3], entity: u32, world: u32, npc: i32, map: u32, region: u32,
             hp: i32, model: u32, boss: bool, completion: u32, runes: u32) -> Reward {
    let bits = pos.map(|v| (v as f32).to_bits() as i32);
    [proto::InputEvent { kind: proto::IN_LOOT_POS, code: 0, a: bits[0], b: bits[1], c: bits[2] },
     proto::InputEvent { kind: proto::IN_LOOT_ID, code: 0, a: entity as i32, b: world as i32, c: npc },
     proto::InputEvent { kind: proto::IN_LOOT_REGION, code: 0, a: map as i32, b: region as i32, c: runes.min(i32::MAX as u32) as i32 },
     proto::InputEvent { kind: proto::IN_ENEMY_DIED, code: if boss { proto::ENEMY_BOSS } else { 0 }, a: hp, b: model as i32, c: completion as i32 }]
}
pub fn known_boss(chr: &ChrIns) -> bool {
    let entity = chr.event_entity_id;
    BOSSES.iter().any(|boss| boss.matches(entity, i32::from(chr.block_id()) as u32, chr.npc_param_id))
}
struct Seen { at: Instant, pos: [f64; 3], world: u32, map: u32, region: u32 }
pub struct BossRewards {
    flags: HashMap<u32, bool>, seen: HashMap<u32, Seen>, completed: HashSet<u32>, checked: Option<Instant>,
}
impl BossRewards {
    pub fn new() -> Self { Self { flags: HashMap::new(), seen: HashMap::new(), completed: HashSet::new(), checked: None } }
    pub fn frame(&mut self, player: &PlayerIns, space: &Space, near: &[NonNull<ChrIns>]) -> Vec<Reward> {
        let Ok(man) = (unsafe { CSEventFlagMan::instance() }) else { return Vec::new(); };
        let flags = &man.virtual_memory_flag;
        if flags.event_flag_divisor != 1000 || flags.event_flag_holder_size != 125 || flags.flag_blocks.is_null() { return Vec::new(); }
        // Remember only an encounter actually seen alive near this player. No retroactive payouts
        // for flags already set when attaching, and no rewards for unrelated distant scripted flags.
        for &ptr in near {
            let chr = unsafe { ptr.as_ref() };
            if chr.modules.data.hp <= 0 || chr.chr_flags1c5.death_flag() { continue; }
            for boss in BOSSES {
                if !boss.matches(chr.event_entity_id, i32::from(chr.block_id()) as u32, chr.npc_param_id) { continue; }
                if flags.get_flag(boss.flag) { continue; }
                self.flags.entry(boss.flag).or_insert(false);
                let h = chr.modules.physics.position;
                self.seen.insert(boss.flag, Seen { at: Instant::now(), pos: space.havok_to_mc([h.0,h.1,h.2]),
                    world: space.world_id, map: i32::from(chr.block_id()) as u32, region: player.play_region_id });
            }
        }
        if self.checked.is_some_and(|at| at.elapsed() < Duration::from_millis(250)) { return Vec::new(); }
        self.checked = Some(Instant::now());
        self.seen.retain(|_,seen| seen.at.elapsed() < Duration::from_secs(600));
        let mut rewards = Vec::new();
        for boss in BOSSES {
            let now = flags.get_flag(boss.flag);
            let before = self.flags.insert(boss.flag,now);
            if before != Some(false) || !now || self.completed.contains(&boss.flag) { continue; }
            let Some(seen) = self.seen.remove(&boss.flag) else { continue; };
            if !seen.pos.iter().all(|v|v.is_finite()) { continue; }
            self.completed.insert(boss.flag);
            log::line(&format!("loot: boss completion flag {} observed; one encounter reward",boss.flag));
            rewards.push(batch(seen.pos,boss.flag,seen.world,boss.npc,seen.map,seen.region,0,boss.model,true,boss.flag,0));
        }
        rewards
    }
}

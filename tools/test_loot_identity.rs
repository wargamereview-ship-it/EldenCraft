#[path = "../game/src/loot_catalog.rs"] mod catalog;
#[test] fn ordinary_zero_id_soldier_is_not_a_boss() {
    assert!(!catalog::BOSSES.iter().any(|b| b.matches(0, 0x3c2a2500, 43111110)));
}
#[test] fn zero_cannot_match_an_actor_list() {
    let b = catalog::Boss { flag: 1, map: 42, model: 1, npc: 12, actors: &[0, 100] };
    assert!(!b.matches(0, 43, 12));
    assert!(!b.matches(0, 42, 13));
    assert!(b.matches(0, 42, 12));
    assert!(b.matches(100, 43, 13));
}
#[test] fn every_catalogued_actor_still_matches() {
    for b in catalog::BOSSES {
        assert!(!b.actors.contains(&0));
        for &id in b.actors { assert!(b.matches(id, b.map, b.npc)); }
    }
}

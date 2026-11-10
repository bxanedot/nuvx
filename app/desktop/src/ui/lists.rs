use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use crate::backend::Collection;
use crate::model::Track;

pub const LIKED_MUSIC: &str = "LM";

const REFRESH_EVERY: Duration = Duration::from_secs(5 * 60);

const KEEP_OPENED: usize = 8;

pub struct Opened {
    pub c: Collection,
    pub load: u64,
    at: Instant,
}

struct ListLoad {
    load: u64,
    album: bool,
    stale: bool,
}

#[derive(Debug, PartialEq)]
pub struct Start {
    pub id: String,
    pub load: u64,
    pub album: bool,
    pub stream: bool,
}

#[derive(Debug, PartialEq)]
pub enum Done {
    Kept,
    Reload(Start),
    NotKept,
}

#[derive(Debug, PartialEq)]
pub enum Arrival {
    More,
    Done(Done),
}

pub struct Page {
    pub tracks: Vec<Track>,
    pub kept: bool,
    pub done: Option<Done>,
}

#[derive(Default)]
pub struct Lists {
    opened: Vec<Opened>,
    loads: u64,
    running: HashMap<String, ListLoad>,
    pub edits: RecentEdits,
}

impl Lists {
    pub fn get(&self, id: &str) -> Option<&Opened> {
        self.opened.iter().find(|o| o.c.id == id)
    }

    pub fn collection_mut(&mut self, id: &str) -> Option<&mut Collection> {
        self.opened.iter_mut().find(|o| o.c.id == id).map(|o| &mut o.c)
    }

    pub fn loading(&self, id: &str) -> bool {
        self.running.contains_key(id)
    }

    pub fn receiving(&self, id: &str) -> bool {
        self.running.get(id).is_some_and(|l| self.get(id).is_some_and(|o| o.load == l.load))
    }

    pub fn move_to_front(&mut self, id: &str) {
        if let Some(i) = self.opened.iter().position(|o| o.c.id == id) {
            let o = self.opened.remove(i);
            self.opened.insert(0, o);
        }
    }

    pub fn load(&mut self, id: &str, album: bool, changed: bool) -> Option<Start> {
        if let Some(l) = self.running.get_mut(id) {
            l.stale |= changed;
            log::debug!("list {id}: load {} under way, stale={}", l.load, l.stale);
            return None;
        }
        self.loads += 1;
        let load = self.loads;
        log::debug!("list {id}: load {load} (kept copy: {}, changed: {changed})", self.get(id).is_some());
        self.running.insert(id.to_owned(), ListLoad { load, album, stale: false });
        Some(Start { id: id.to_owned(), load, album, stream: !album && self.get(id).is_none() })
    }

    pub fn visit(&mut self, id: &str, album: bool, now: Instant) -> Option<Start> {
        match self.get(id).map(|o| now.saturating_duration_since(o.at)) {
            Some(age) if age <= REFRESH_EVERY => {
                log::debug!("list {id}: kept copy is {age:?} old, not reloading");
                None
            }
            _ => self.load(id, album, false),
        }
    }

    pub fn arrived(&mut self, load: u64, mut c: Collection, more: bool, keep: &[&str]) -> Option<Arrival> {
        if self.running.get(&c.id).is_none_or(|l| l.load != load) {
            return None;
        }
        let sent = c.tracks.len();
        self.edits.correct_collection(&mut c);
        let id = c.id.clone();
        log::debug!("list {id}: load {load} in, {sent} songs sent, {} after local edits, more={more}", c.tracks.len());
        let at = Instant::now();
        match self.opened.iter_mut().find(|o| o.c.id == id) {
            Some(o) => *o = Opened { c, load, at },
            None => self.opened.insert(0, Opened { c, load, at }),
        }
        self.trim(keep);
        if more {
            return Some(Arrival::More);
        }
        self.done(&id, keep).map(Arrival::Done)
    }

    pub fn page(&mut self, id: &str, load: u64, tracks: Vec<Track>, set_ids: Vec<Option<String>>, more: bool, keep: &[&str]) -> Option<Page> {
        if self.running.get(id).is_none_or(|l| l.load != load) {
            return None;
        }
        self.edits.prune();
        let sent = tracks.len();
        let edits = &self.edits;
        let (mut tracks, mut set_ids): (Vec<Track>, Vec<Option<String>>) = tracks.into_iter().zip(set_ids).filter(|(t, set)| edits.keeps(id, t, set.as_deref())).unzip();
        let kept = match self.opened.iter_mut().find(|o| o.c.id == id && o.load == load) {
            Some(o) => {
                if id == LIKED_MUSIC {
                    (tracks, set_ids) = tracks.into_iter().zip(set_ids).filter(|(t, _)| !o.c.tracks.iter().any(|x| x.id == t.id)).unzip();
                }
                o.c.tracks.extend(tracks.iter().cloned());
                o.c.set_ids.extend(set_ids);
                log::debug!("list {id}: load {load} page, {sent} sent, {} added, {} total, more={more}", tracks.len(), o.c.tracks.len());
                true
            }
            None => {
                log::debug!("list {id}: load {load} page of {sent} for no kept copy (replaced when complete)");
                false
            }
        };
        let done = if more { None } else { self.done(id, keep) };
        Some(Page { tracks, kept, done })
    }

    fn done(&mut self, id: &str, keep: &[&str]) -> Option<Done> {
        let l = self.running.remove(id)?;
        self.trim(keep);
        Some(match (self.get(id).is_some(), l.stale) {
            (false, _) => Done::NotKept,
            (true, true) => Done::Reload(self.load(id, l.album, false)?),
            (true, false) => Done::Kept,
        })
    }

    fn trim(&mut self, keep: &[&str]) {
        let mut i = self.opened.len();
        while self.opened.len() > KEEP_OPENED && i > 0 {
            i -= 1;
            let id = self.opened[i].c.id.as_str();
            if !keep.contains(&id) && id != LIKED_MUSIC && !self.running.contains_key(id) {
                self.opened.remove(i);
            }
        }
    }

    pub fn set_liked(&mut self, tracks: &[Track], like: bool) {
        for t in tracks {
            self.edits.like(&t.id, like, Some(t.clone()));
        }
        let Some(c) = self.collection_mut(LIKED_MUSIC) else { return };
        for t in tracks.iter().rev() {
            match (like, c.tracks.iter().position(|x| x.id == t.id)) {
                (true, None) => {
                    c.tracks.insert(0, t.clone());
                    c.set_ids.insert(0, None);
                    c.count = c.count.map(|n| n + 1);
                }
                (false, Some(i)) => c.remove_rows(&[i]),
                _ => {}
            }
        }
    }

    pub fn known(&self, id: &str) -> bool {
        self.get(id).is_some() || self.loading(id)
    }

    pub fn forget(&mut self, id: &str) {
        self.opened.retain(|o| o.c.id != id);
        self.running.remove(id);
    }

    pub fn clear(&mut self) {
        self.opened.clear();
        self.running.clear();
    }
}

const EDIT_LAG: Duration = Duration::from_secs(180);
const REMOVAL_UNANSWERED: Duration = Duration::from_secs(60);
const LIKE_UNANSWERED: Duration = Duration::from_secs(600);

#[derive(Default)]
pub struct RecentEdits {
    likes: HashMap<String, (bool, Option<Track>, EditAge)>,
    removals: HashMap<String, (HashSet<String>, EditAge)>,
}

#[derive(Clone, Copy)]
struct EditAge {
    made: Instant,
    confirmed: Option<Instant>,
}

impl EditAge {
    fn new() -> Self {
        Self { made: Instant::now(), confirmed: None }
    }

    fn live(&self, unanswered: Duration) -> bool {
        match self.confirmed {
            Some(t) => t.elapsed() < EDIT_LAG,
            None => self.made.elapsed() < unanswered,
        }
    }
}

impl RecentEdits {
    fn like(&mut self, id: &str, like: bool, track: Option<Track>) {
        self.likes.insert(id.to_owned(), (like, track, EditAge::new()));
    }

    pub fn confirm_likes(&mut self, done: &[String], refused: &[String]) {
        for id in done {
            if let Some((_, _, age)) = self.likes.get_mut(id) {
                age.confirmed = Some(Instant::now());
            }
        }
        for id in refused {
            self.likes.remove(id);
        }
    }

    pub fn remove(&mut self, playlist: &str, set_ids: impl IntoIterator<Item = String>) {
        let (set, age) = self.removals.entry(playlist.to_owned()).or_insert_with(|| (HashSet::new(), EditAge::new()));
        set.extend(set_ids);
        *age = EditAge::new();
    }

    pub fn confirm_removal(&mut self, playlist: &str) {
        if let Some((_, age)) = self.removals.get_mut(playlist) {
            age.confirmed = Some(Instant::now());
        }
    }

    fn prune(&mut self) {
        self.likes.retain(|_, (_, _, age)| age.live(LIKE_UNANSWERED));
        self.removals.retain(|_, (_, age)| age.live(REMOVAL_UNANSWERED));
    }

    fn keeps(&self, list: &str, t: &Track, set_id: Option<&str>) -> bool {
        let unliked = list == LIKED_MUSIC && self.likes.get(&t.id).is_some_and(|(like, _, _)| !*like);
        let taken = set_id.is_some_and(|s| self.removals.get(list).is_some_and(|(r, _)| r.contains(s)));
        !unliked && !taken
    }

    fn correct_collection(&mut self, c: &mut Collection) {
        self.prune();
        log::debug!(
            "edits: {} likes, {} unlikes, {} removals for {} pending",
            self.likes.values().filter(|(l, _, _)| *l).count(),
            self.likes.values().filter(|(l, _, _)| !*l).count(),
            self.removals.get(&c.id).map_or(0, |(r, _)| r.len()),
            c.id
        );
        let keep: Vec<bool> = c.tracks.iter().enumerate().map(|(i, t)| self.keeps(&c.id, t, c.set_ids.get(i).and_then(Option::as_deref))).collect();
        let dropped: Vec<usize> = keep.iter().enumerate().filter(|(_, k)| !**k).map(|(i, _)| i).collect();
        c.remove_rows(&dropped);
        if c.id == LIKED_MUSIC {
            for (id, (like, track, _)) in &self.likes {
                if let (true, Some(t)) = (*like, track)
                    && !c.tracks.iter().any(|x| &x.id == id)
                {
                    c.tracks.insert(0, t.clone());
                    c.set_ids.insert(0, None);
                    c.count = c.count.map(|n| n + 1);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use super::{Arrival, Collection, Done, LIKED_MUSIC, Lists, RecentEdits, Start, Track};

    fn track(id: &str) -> Track {
        Track { id: id.into(), title: id.into(), artists: vec![], album: None, duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false }
    }

    fn collection(id: &str, songs: &[&str]) -> Collection {
        Collection {
            id: id.into(),
            is_album: false,
            save_id: None,
            title: String::new(),
            subtitle: String::new(),
            artists: vec![],
            description: None,
            thumbs: vec![],
            tracks: songs.iter().map(|s| track(s)).collect(),
            set_ids: songs.iter().map(|s| Some(format!("set-{s}"))).collect(),
            count: Some(songs.len() as u64),
            owned: true,
        }
    }

    fn songs(lists: &Lists, id: &str) -> Vec<String> {
        lists.get(id).map(|o| o.c.tracks.iter().map(|t| t.id.clone()).collect()).unwrap_or_default()
    }

    fn load_whole(lists: &mut Lists, id: &str, songs: &[&str], keep: &[&str]) {
        let s = lists.load(id, false, false).unwrap();
        assert_eq!(lists.arrived(s.load, collection(id, songs), false, keep), Some(Arrival::Done(Done::Kept)));
    }

    fn page_of(songs: &[&str]) -> (Vec<Track>, Vec<Option<String>>) {
        (songs.iter().map(|s| track(s)).collect(), songs.iter().map(|s| Some(format!("set-{s}"))).collect())
    }

    #[test]
    fn only_the_load_under_way_is_taken() {
        let mut lists = Lists::default();
        let s = lists.load("PL1", false, false).unwrap();
        assert_eq!(s, Start { id: "PL1".into(), load: 1, album: false, stream: true });
        assert_eq!(lists.load("PL1", false, false), None);
        assert_eq!(lists.arrived(7, collection("PL1", &["a"]), true, &[]), None);
        assert!(lists.get("PL1").is_none());

        assert_eq!(lists.arrived(1, collection("PL1", &["a"]), true, &[]), Some(Arrival::More));
        assert!(lists.receiving("PL1"));
        let (t, set) = page_of(&["b"]);
        assert!(lists.page("PL1", 7, t.clone(), set.clone(), false, &[]).is_none());
        let page = lists.page("PL1", 1, t, set, false, &[]).unwrap();
        assert!(page.kept);
        assert_eq!(page.done, Some(Done::Kept));
        assert_eq!(songs(&lists, "PL1"), ["a", "b"]);
        assert!(!lists.receiving("PL1") && !lists.loading("PL1"));
        let (t, set) = page_of(&["c"]);
        assert!(lists.page("PL1", 1, t, set, false, &[]).is_none());
    }

    #[test]
    fn a_change_during_a_load_loads_it_again() {
        let mut lists = Lists::default();
        let s = lists.load("PL1", false, false).unwrap();
        assert_eq!(lists.load("PL1", false, true), None);
        let again = lists.arrived(s.load, collection("PL1", &["a"]), false, &[]).unwrap();
        assert_eq!(again, Arrival::Done(Done::Reload(Start { id: "PL1".into(), load: 2, album: false, stream: false })));
        assert!(lists.loading("PL1"));

        let s = lists.load("PL2", false, false).unwrap();
        lists.arrived(s.load, collection("PL2", &["a"]), true, &[]).unwrap();
        assert_eq!(lists.load("PL2", false, true), None);
        let page = lists.page("PL2", s.load, vec![], vec![], false, &[]).unwrap();
        assert!(matches!(page.done, Some(Done::Reload(Start { stream: false, .. }))));
        assert_eq!(songs(&lists, "PL2"), ["a"]);
    }

    #[test]
    fn a_load_that_fails_before_anything_came() {
        let mut lists = Lists::default();
        let s = lists.load("PL1", false, false).unwrap();
        let page = lists.page("PL1", s.load, vec![], vec![], false, &[]).unwrap();
        assert!(!page.kept);
        assert_eq!(page.done, Some(Done::NotKept));
        assert!(!lists.known("PL1"));

        let s = lists.load("PL3", false, false).unwrap();
        lists.arrived(s.load, collection("PL3", &["a"]), true, &[]).unwrap();
        assert_eq!(lists.page("PL3", s.load, vec![], vec![], false, &[]).unwrap().done, Some(Done::Kept));
        assert_eq!(songs(&lists, "PL3"), ["a"]);
        assert!(!lists.receiving("PL3"));

        load_whole(&mut lists, "PL2", &["a"], &[]);
        let s = lists.load("PL2", false, false).unwrap();
        assert_eq!(lists.page("PL2", s.load, vec![], vec![], false, &[]).unwrap().done, Some(Done::Kept));
        assert_eq!(songs(&lists, "PL2"), ["a"]);
    }

    #[test]
    fn a_kept_copy_is_swapped_when_the_new_one_is_in() {
        let mut lists = Lists::default();
        load_whole(&mut lists, "PL1", &["a", "b"], &[]);
        let s = lists.load("PL1", false, false).unwrap();
        assert!(!s.stream);
        assert_eq!(lists.arrived(s.load, collection("PL1", &["x", "a"]), false, &[]), Some(Arrival::Done(Done::Kept)));
        assert_eq!(songs(&lists, "PL1"), ["x", "a"]);

        let s = lists.load("PL1", false, false).unwrap();
        let (t, set) = page_of(&["y"]);
        let page = lists.page("PL1", s.load, t, set, true, &[]).unwrap();
        assert!(!page.kept);
        assert_eq!(page.tracks.len(), 1);
        assert_eq!(songs(&lists, "PL1"), ["x", "a"]);
        assert!(!lists.receiving("PL1"));
        lists.arrived(s.load, collection("PL1", &["y", "x", "a"]), false, &[]).unwrap();
        assert_eq!(songs(&lists, "PL1"), ["y", "x", "a"]);
    }

    #[test]
    fn pages_leave_out_edits_made_here() {
        let mut lists = Lists::default();
        let s = lists.load(LIKED_MUSIC, false, false).unwrap();
        lists.arrived(s.load, collection(LIKED_MUSIC, &["a"]), true, &[]).unwrap();
        lists.set_liked(&[track("z")], true);
        lists.set_liked(&[track("u")], false);
        assert_eq!(songs(&lists, LIKED_MUSIC), ["z", "a"]);
        let (t, set) = page_of(&["b", "z", "u"]);
        let page = lists.page(LIKED_MUSIC, s.load, t, set, false, &[]).unwrap();
        assert_eq!(page.tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["b"]);
        assert_eq!(songs(&lists, LIKED_MUSIC), ["z", "a", "b"]);

        let s = lists.load("PL1", false, false).unwrap();
        lists.edits.remove("PL1", ["set-c".to_owned()]);
        lists.arrived(s.load, collection("PL1", &["c", "d"]), true, &[]).unwrap();
        let (t, set) = page_of(&["c", "e"]);
        lists.page("PL1", s.load, t, set, false, &[]).unwrap();
        assert_eq!(songs(&lists, "PL1"), ["d", "e"]);
    }

    #[test]
    fn room_is_made_from_the_oldest_but_not_what_is_in_use() {
        let mut lists = Lists::default();
        load_whole(&mut lists, LIKED_MUSIC, &["a"], &[]);
        load_whole(&mut lists, "PL0", &["a"], &[]);
        let loading = lists.load("PL1", false, false).unwrap();
        lists.arrived(loading.load, collection("PL1", &["a"]), true, &[]).unwrap();
        for i in 2..10 {
            load_whole(&mut lists, &format!("PL{i}"), &["a"], &["PL0"]);
        }
        let kept: Vec<&str> = lists.opened.iter().map(|o| o.c.id.as_str()).collect();
        assert_eq!(kept, ["PL9", "PL8", "PL7", "PL6", "PL5", "PL1", "PL0", LIKED_MUSIC]);

    }

    #[test]
    fn a_load_ending_with_no_room_left_is_dropped() {
        let mut lists = Lists::default();
        load_whole(&mut lists, LIKED_MUSIC, &["a"], &[]);
        let keep = ["PL9", "PL8"];
        let starts: Vec<_> = (1..=9).map(|i| lists.load(&format!("PL{i}"), false, false).unwrap()).collect();
        for s in &starts {
            lists.arrived(s.load, collection(&s.id, &["a"]), true, &keep).unwrap();
        }
        assert_eq!(lists.opened.len(), 10);
        assert_eq!(lists.load("PL1", false, true), None);
        let page = lists.page("PL1", starts[0].load, vec![], vec![], false, &keep).unwrap();
        assert_eq!(page.done, Some(Done::NotKept));
        assert!(!lists.known("PL1"));
        let page = lists.page("PL8", starts[7].load, vec![], vec![], false, &keep).unwrap();
        assert_eq!(page.done, Some(Done::Kept));
        assert_eq!(lists.opened.len(), 9);
    }

    #[test]
    fn going_back_reloads_only_an_old_copy() {
        let mut lists = Lists::default();
        let now = Instant::now();
        assert!(lists.visit("PL1", false, now).is_some());
        assert_eq!(lists.visit("PL1", false, now), None);
        lists.arrived(1, collection("PL1", &["a"]), false, &[]).unwrap();
        assert_eq!(lists.visit("PL1", false, Instant::now()), None);
        assert!(lists.visit("PL1", false, Instant::now() + Duration::from_secs(6 * 60)).is_some());
    }

    #[test]
    fn another_account_starts_over() {
        let mut lists = Lists::default();
        load_whole(&mut lists, "PL1", &["a"], &[]);
        let s = lists.load("PL2", false, false).unwrap();
        lists.clear();
        assert!(!lists.known("PL1") && !lists.known("PL2"));
        let again = lists.load("PL2", false, false).unwrap();
        assert_ne!(again.load, s.load);
        assert_eq!(lists.arrived(s.load, collection("PL2", &["a"]), false, &[]), None);
        lists.forget("PL2");
        assert_eq!(lists.arrived(again.load, collection("PL2", &["a"]), false, &[]), None);
    }

    #[test]
    fn stale_lists_drop_recent_removals_and_unlikes() {
        let mut edits = RecentEdits::default();
        edits.remove("PL1", ["set-b".to_owned(), "set-c".to_owned()]);
        edits.confirm_removal("PL1");
        let mut c = collection("PL1", &["a", "b", "c", "d"]);
        edits.correct_collection(&mut c);
        assert_eq!(c.tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["a", "d"]);
        assert_eq!(c.set_ids, [Some("set-a".to_owned()), Some("set-d".to_owned())]);
        assert_eq!(c.count, Some(2));
        let mut other = collection("PL2", &["b"]);
        edits.correct_collection(&mut other);
        assert_eq!(other.tracks.len(), 1);

        edits.like("x", false, None);
        edits.like("y", true, Some(track("y")));
        edits.like("z", false, None);
        edits.confirm_likes(&["x".to_owned(), "y".to_owned()], &["z".to_owned()]);
        let mut lm = collection("LM", &["x", "z", "w"]);
        edits.correct_collection(&mut lm);
        assert_eq!(lm.tracks.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), ["y", "z", "w"]);
        assert_eq!(lm.set_ids.len(), 3);
        assert_eq!(lm.count, Some(3));
        assert_eq!(lm.byline(), "3 songs");
        assert!(!edits.keeps("LM", &track("x"), Some("set-x")));
        assert!(edits.keeps("PL9", &track("x"), Some("set-x")));
    }
}

use crate::model::Track;

#[derive(Clone, Copy, PartialEq)]
pub enum Repeat {
    Off,
    All,
    One,
}

pub struct QueueFill {
    pub id: String,
    pub load: u64,
    pub shuffled: bool,
    pub ran_out: bool,
}

pub struct Filled {
    pub ran_out: bool,
    pub was_at_end: bool,
}

pub struct Queue {
    pub tracks: Vec<Track>,
    pub index: Option<usize>,
    pub repeat: Repeat,
    pub fill: Option<QueueFill>,
}

const SAVED_BEFORE: usize = 50;
const SAVED_AFTER: usize = 150;

impl Queue {
    pub fn new(repeat: Repeat) -> Self {
        Self { tracks: Vec::new(), index: None, repeat, fill: None }
    }

    pub fn current(&self) -> Option<&Track> {
        self.index.and_then(|i| self.tracks.get(i))
    }

    pub fn at_end(&self) -> bool {
        self.index.is_some_and(|i| i + 1 >= self.tracks.len())
    }

    pub fn replace(&mut self, tracks: Vec<Track>, start: usize, fill: Option<QueueFill>) -> bool {
        if tracks.is_empty() {
            return false;
        }
        self.fill = fill;
        self.index = Some(start.min(tracks.len() - 1));
        self.tracks = tracks;
        true
    }

    pub fn adopt(&mut self, tracks: Vec<Track>, index: usize) {
        self.tracks = tracks;
        self.index = Some(index);
        self.fill = None;
    }

    pub fn advance(&mut self) -> bool {
        let Some(i) = self.index else { return false };
        if i + 1 < self.tracks.len() {
            self.index = Some(i + 1);
        } else if let Some(fill) = &mut self.fill {
            fill.ran_out = true;
            return false;
        } else if self.repeat == Repeat::All && !self.tracks.is_empty() {
            self.index = Some(0);
        } else {
            return false;
        }
        true
    }

    pub fn back(&mut self) -> bool {
        match self.index.filter(|&i| i > 0) {
            Some(i) => {
                self.index = Some(i - 1);
                true
            }
            None => false,
        }
    }

    pub fn upcoming(&self) -> Option<&Track> {
        let i = self.index?;
        match self.repeat {
            Repeat::One => None,
            Repeat::All => self.tracks.get(i + 1).or(self.tracks.first()),
            Repeat::Off => self.tracks.get(i + 1),
        }
    }

    pub fn started(&mut self) {
        if let Some(fill) = &mut self.fill {
            fill.ran_out = false;
        }
    }

    pub fn shuffle_upcoming(&mut self) -> bool {
        let start = self.index.map_or(0, |i| i + 1);
        if start >= self.tracks.len() {
            return false;
        }
        super::shuffle(&mut self.tracks[start..]);
        if let Some(fill) = &mut self.fill {
            fill.shuffled = true;
        }
        true
    }

    pub fn play_next(&mut self, t: Track) -> bool {
        let at = self.index.map_or(0, |i| i + 1).min(self.tracks.len());
        self.tracks.insert(at, t);
        if self.index.is_some() {
            return false;
        }
        self.index = Some(0);
        true
    }

    pub fn enqueue(&mut self, t: Track) -> bool {
        self.tracks.push(t);
        if self.index.is_some() {
            return false;
        }
        self.index = Some(self.tracks.len() - 1);
        true
    }

    pub fn remove(&mut self, i: usize) {
        if i < self.tracks.len() && Some(i) != self.index {
            self.tracks.remove(i);
            if let Some(cur) = self.index.filter(|&c| c > i) {
                self.index = Some(cur - 1);
            }
        }
    }

    pub fn add_radio(&mut self, seed: &str, tracks: Vec<Track>) -> bool {
        let have: std::collections::HashSet<String> = self.tracks.iter().map(|t| t.id.clone()).collect();
        let was_at_end = self.at_end();
        self.tracks.extend(tracks.into_iter().filter(|t| t.id != seed && !have.contains(&t.id)));
        was_at_end
    }

    pub fn fill_from(&mut self, id: &str, load: u64, tracks: Vec<Track>, more: bool, playing_it: bool) -> Option<Filled> {
        let fill = self.fill.as_mut().filter(|f| f.id == id && f.load == load)?;
        let (shuffled, ran_out) = (fill.shuffled, std::mem::take(&mut fill.ran_out));
        if !more {
            self.fill = None;
        }
        if !playing_it {
            return None;
        }
        let was_at_end = self.at_end();
        self.tracks.extend(tracks);
        if shuffled {
            let from = self.index.map_or(0, |i| i + if was_at_end { 1 } else { 2 }).min(self.tracks.len());
            super::shuffle(&mut self.tracks[from..]);
        }
        Some(Filled { ran_out, was_at_end })
    }

    pub fn saved(&self) -> Option<(&[Track], usize)> {
        let i = self.index?;
        let from = i.saturating_sub(SAVED_BEFORE);
        let to = (i + SAVED_AFTER).min(self.tracks.len());
        Some((&self.tracks[from..to], i - from))
    }
}

#[cfg(test)]
mod tests {
    use super::{Queue, QueueFill, Repeat, Track};

    fn track(id: &str) -> Track {
        Track { id: id.into(), title: id.into(), artists: vec![], album: None, duration: None, thumbs: vec![], track_nr: None, plays: None, music_video: false }
    }

    fn queue(songs: &[&str], index: usize, repeat: Repeat) -> Queue {
        let mut q = Queue::new(repeat);
        assert!(q.replace(songs.iter().map(|s| track(s)).collect(), index, None));
        q
    }

    fn ids(q: &Queue) -> Vec<&str> {
        q.tracks.iter().map(|t| t.id.as_str()).collect()
    }

    fn filling(id: &str, load: u64, shuffled: bool) -> Option<QueueFill> {
        Some(QueueFill { id: id.into(), load, shuffled, ran_out: false })
    }

    #[test]
    fn next_and_back_follow_repeat() {
        let mut q = queue(&["a", "b"], 0, Repeat::Off);
        assert!(q.advance());
        assert_eq!(q.index, Some(1));
        assert!(!q.advance());
        assert_eq!(q.index, Some(1));
        q.repeat = Repeat::All;
        assert!(q.advance());
        assert_eq!(q.index, Some(0));
        q.repeat = Repeat::One;
        assert!(q.advance());
        assert_eq!(q.index, Some(1));
        assert!(q.back());
        assert!(!q.back());
        assert_eq!(q.index, Some(0));
        assert!(!Queue::new(Repeat::All).advance());
    }

    #[test]
    fn what_plays_next_is_fetched_ahead() {
        let q = queue(&["a", "b"], 1, Repeat::Off);
        assert!(q.upcoming().is_none());
        let q = queue(&["a", "b"], 1, Repeat::All);
        assert_eq!(q.upcoming().unwrap().id, "a");
        let q = queue(&["a", "b"], 0, Repeat::One);
        assert!(q.upcoming().is_none());
        let q = queue(&["a", "b"], 0, Repeat::Off);
        assert_eq!(q.upcoming().unwrap().id, "b");
    }

    #[test]
    fn playing_nothing_leaves_the_queue_as_it_was() {
        let mut q = Queue::new(Repeat::Off);
        q.replace(vec![track("a")], 0, filling("PL1", 1, false));
        assert!(!q.replace(vec![], 0, None));
        assert_eq!(ids(&q), ["a"]);
        assert_eq!(q.fill.as_ref().unwrap().id, "PL1");
        assert!(q.replace(vec![track("x"), track("y")], 9, None));
        assert_eq!(q.current().unwrap().id, "y");
    }

    #[test]
    fn songs_added_by_hand() {
        let mut q = Queue::new(Repeat::Off);
        assert!(q.enqueue(track("a")));
        assert_eq!(q.index, Some(0));
        assert!(!q.enqueue(track("c")));
        assert!(!q.play_next(track("b")));
        assert_eq!(ids(&q), ["a", "b", "c"]);
        let mut empty = Queue::new(Repeat::Off);
        assert!(empty.play_next(track("x")));
        assert_eq!(empty.current().unwrap().id, "x");

        q.index = Some(2);
        q.remove(0);
        assert_eq!(ids(&q), ["b", "c"]);
        assert_eq!(q.current().unwrap().id, "c");
        q.remove(1);
        q.remove(5);
        assert_eq!(ids(&q), ["b", "c"]);
    }

    #[test]
    fn radio_adds_only_new_songs() {
        let mut q = queue(&["seed", "a"], 1, Repeat::Off);
        assert!(q.add_radio("seed", vec![track("seed"), track("a"), track("b"), track("c")]));
        assert_eq!(ids(&q), ["seed", "a", "b", "c"]);
        assert!(!q.add_radio("seed", vec![track("d")]));
    }

    #[test]
    fn a_playlist_still_coming_in_fills_the_queue() {
        let mut q = Queue::new(Repeat::Off);
        q.replace(vec![track("a"), track("b")], 0, filling("PL1", 3, false));
        assert!(q.fill_from("PL2", 3, vec![track("x")], true, true).is_none());
        assert!(q.fill_from("PL1", 2, vec![track("x")], true, true).is_none());
        let f = q.fill_from("PL1", 3, vec![track("c")], true, true).unwrap();
        assert!(!f.ran_out && !f.was_at_end);
        assert_eq!(ids(&q), ["a", "b", "c"]);

        q.index = Some(2);
        assert!(!q.advance());
        assert!(q.fill.as_ref().unwrap().ran_out);
        let f = q.fill_from("PL1", 3, vec![track("d")], false, true).unwrap();
        assert!(f.ran_out && f.was_at_end);
        assert!(q.fill.is_none());
        assert!(q.advance());
        assert_eq!(q.current().unwrap().id, "d");
    }

    #[test]
    fn waiting_for_a_playlist_comes_before_repeating_it() {
        let mut q = Queue::new(Repeat::All);
        q.replace(vec![track("a"), track("b")], 1, filling("PL1", 1, false));
        assert!(!q.advance());
        assert_eq!(q.index, Some(1));
        let f = q.fill_from("PL1", 1, vec![], false, true).unwrap();
        assert!(f.ran_out && f.was_at_end);
        assert!(q.fill.is_none());
        assert!(q.advance());
        assert_eq!(q.index, Some(0));
    }

    #[test]
    fn filling_stops_when_something_else_plays() {
        let mut q = Queue::new(Repeat::Off);
        q.replace(vec![track("a")], 0, filling("PL1", 1, false));
        assert!(q.fill_from("PL1", 1, vec![track("b")], true, false).is_none());
        assert!(q.fill.is_some());
        assert!(q.fill_from("PL1", 1, vec![track("c")], false, false).is_none());
        assert!(q.fill.is_none());
        assert_eq!(ids(&q), ["a"]);
        q.replace(vec![track("a")], 0, filling("PL1", 2, false));
        q.advance();
        q.started();
        assert!(!q.fill.as_ref().unwrap().ran_out);
    }

    #[test]
    fn shuffling_up_next_leaves_what_played() {
        let songs: Vec<String> = (0..40).map(|i| i.to_string()).collect();
        let refs: Vec<&str> = songs.iter().map(String::as_str).collect();
        let mut q = queue(&refs, 5, Repeat::Off);
        q.fill = filling("PL1", 1, false);
        assert!(q.shuffle_upcoming());
        assert!(q.fill.as_ref().unwrap().shuffled);
        assert_eq!(ids(&q)[..6], refs[..6]);
        let mut rest: Vec<&str> = ids(&q)[6..].to_vec();
        assert_ne!(rest, refs[6..]);
        rest.sort_by_key(|s| s.parse::<u32>().unwrap());
        assert_eq!(rest, refs[6..]);
        let mut q = queue(&["a", "b"], 1, Repeat::Off);
        assert!(!q.shuffle_upcoming());
    }

    #[test]
    fn a_shuffled_playlists_next_pages_mix_in_after_the_next_song() {
        let songs: Vec<String> = (0..40).map(|i| i.to_string()).collect();
        let refs: Vec<&str> = songs.iter().map(String::as_str).collect();
        let mut q = queue(&refs[..10], 2, Repeat::Off);
        q.fill = filling("PL1", 1, true);
        let more: Vec<Track> = (10..40).map(|i| track(&i.to_string())).collect();
        q.fill_from("PL1", 1, more, true, true).unwrap();
        assert_eq!(ids(&q)[..4], refs[..4]);
        let mut rest: Vec<&str> = ids(&q)[4..].to_vec();
        assert_ne!(rest, refs[4..]);
        rest.sort_by_key(|s| s.parse::<u32>().unwrap());
        assert_eq!(rest, refs[4..]);
    }

    #[test]
    fn a_saved_session_keeps_the_songs_around_the_current_one() {
        let songs: Vec<String> = (0..300).map(|i| i.to_string()).collect();
        let refs: Vec<&str> = songs.iter().map(String::as_str).collect();
        let q = queue(&refs, 120, Repeat::Off);
        let (saved, i) = q.saved().unwrap();
        assert_eq!((saved.len(), i), (200, 50));
        assert_eq!(saved[i].id, "120");
        let q = queue(&refs[..10], 3, Repeat::Off);
        let (saved, i) = q.saved().unwrap();
        assert_eq!((saved.len(), i), (10, 3));
        assert!(Queue::new(Repeat::Off).saved().is_none());
    }
}

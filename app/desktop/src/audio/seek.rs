use super::{AudioCmd, AudioEvent};
use crate::stream::{self, Shared};
use crossbeam_channel::Sender;
use std::sync::Arc;
use std::time::Duration;
pub(super) struct SeekJob {
    pub(super) shared: Arc<Shared>,
    pub(super) pos: Duration,
    pub(super) live: Option<(Duration, std::time::Instant)>,
    pub(super) generation: u64,
    pub(super) seek: u64,
}

pub(super) fn build_seek(j: SeekJob, tx: &Sender<AudioCmd>, events: &Sender<AudioEvent>) {
    let SeekJob { shared, pos, live, generation, seek } = j;
    let seekable = !pos.is_zero();
    let res = stream::decoder(shared.clone(), seekable).map_err(|e| e.to_string()).and_then(|mut d| {
        let pos = live.map_or(pos, |(p, at)| p + at.elapsed());
        if seekable {
            rodio::Source::try_seek(&mut d, pos).map_err(|e| e.to_string())?;
        }
        Ok((d, pos))
    });
    match res {
        Ok((decoder, pos)) => {
            let _ = tx.send(AudioCmd::Replace { generation, seek, decoder, at: pos, seekable });
        }
        Err(e) => match shared.error() {
            Some(reason) => {
                let _ = events.send(AudioEvent::Interrupted { generation, at: pos, reason });
            }
            None => {
                let _ = events.send(AudioEvent::Error(format!("Seek failed: {e}")));
            }
        },
    }
}

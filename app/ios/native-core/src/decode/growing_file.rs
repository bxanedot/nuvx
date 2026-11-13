

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};
use symphonia::core::io::MediaSource;











pub(super) struct GrowingFile {
    pub(super) file: File,
    pub(super) path: PathBuf,
    pub(super) declared_len: Option<u64>,



    pub(super) block: bool,
}

impl GrowingFile {
    pub(super) fn is_growing(path: &str) -> bool {
        Path::new(&format!("{path}.grow")).exists()
            && !Path::new(&format!("{path}.complete")).exists()
    }

    pub(super) fn open(path: &str) -> std::io::Result<Self> {
        Self::open_mode(path, true)
    }

    pub(super) fn open_mode(path: &str, block: bool) -> std::io::Result<Self> {
        let declared_len = std::fs::read_to_string(format!("{path}.len"))
            .ok()
            .and_then(|s| s.trim().parse().ok())
            .filter(|n: &u64| *n > 0);
        Ok(Self {
            file: File::open(path)?,
            path: PathBuf::from(path),
            declared_len,
            block,
        })
    }

    pub(super) fn is_complete(&self) -> bool {
        Path::new(&format!("{}.complete", self.path.display())).exists()
    }

    pub(super) fn wait_for_len(&self, needed: u64) -> std::io::Result<()> {
        let deadline = Instant::now() + Duration::from_secs(120);
        loop {


            if Path::new(&format!("{}.playback-cancelled", self.path.display())).exists() {
                return Err(std::io::Error::new(std::io::ErrorKind::ConnectionAborted, "playback reader cancelled"));
            }
            let len = std::fs::metadata(&self.path)?.len();
            if len >= needed {
                return Ok(());
            }
            if self.is_complete() {
                return if len >= needed {
                    Ok(())
                } else {
                    Err(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "growing file ended short",
                    ))
                };
            }
            if Instant::now() >= deadline {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    "timed out waiting for download",
                ));
            }
            std::thread::sleep(Duration::from_millis(8));
        }
    }
}

impl Read for GrowingFile {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        loop {
            let pos = self.file.stream_position()?;
            let n = self.file.read(buf)?;
            if n > 0 {
                return Ok(n);
            }
            if self.is_complete() || !self.block {
                return Ok(0);
            }
            let len = std::fs::metadata(&self.path)?.len();
            if len > pos {
                self.file.seek(SeekFrom::Start(pos))?;
                continue;
            }
            self.wait_for_len(pos + 1)?;
            self.file.seek(SeekFrom::Start(pos))?;
        }
    }
}

impl Seek for GrowingFile {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let current = self.file.stream_position()?;
        let on_disk = std::fs::metadata(&self.path)
            .ok()
            .map(|m| m.len())
            .unwrap_or(current);
        let end = if self.is_complete() {
            on_disk
        } else {


            on_disk
        };
        let target = match pos {
            SeekFrom::Start(offset) => offset as i64,
            SeekFrom::Current(delta) => current as i64 + delta,
            SeekFrom::End(delta) => end as i64 + delta,
        };
        if target < 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "negative seek",
            ));
        }
        let target = target as u64;
        if self.block {
            self.wait_for_len(target)?;
        } else if !self.is_complete() && target > on_disk {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "analysis read stopped at the bytes downloaded so far",
            ));
        }
        self.file.seek(SeekFrom::Start(target))
    }
}

impl MediaSource for GrowingFile {
    fn is_seekable(&self) -> bool {
        self.is_complete()
    }

    fn byte_len(&self) -> Option<u64> {
        if self.is_complete() {
            return std::fs::metadata(&self.path)
                .ok()
                .map(|m| m.len())
                .or(self.declared_len);
        }


        None
    }
}

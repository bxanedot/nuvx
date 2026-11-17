use std::io::Write;
use std::path::{Path, PathBuf};

enum Kind {
    Main,
    Child(&'static str),
    Dev(PathBuf),
}

pub fn init(dir: &Path) {
    if let Some(path) = std::env::var_os("NUVX_LOG") {
        setup(dir, Kind::Dev(path.into()));
    } else {
        setup(dir, Kind::Main);
    }
}

pub fn init_child(dir: &Path, tag: &'static str) {
    setup(dir, Kind::Child(tag));
}

fn setup(dir: &Path, kind: Kind) {
    let (file, path_note) = match &kind {
        Kind::Main => {
            let _ = std::fs::create_dir_all(dir);
            let path = dir.join("nuvx.log");
            let _ = std::fs::rename(&path, dir.join("nuvx.prev.log"));
            match std::fs::File::create(&path) {
                Ok(f) => (Some(f), path.display().to_string()),
                Err(e) => (None, format!("nowhere (couldn't create {}: {e})", path.display())),
            }
        }
        Kind::Child(tag) => {
            let _ = std::fs::create_dir_all(dir);
            let path = dir.join(format!("nuvx-{tag}.log"));
            match std::fs::OpenOptions::new().create(true).append(true).open(&path) {
                Ok(f) => (Some(f), path.display().to_string()),
                Err(e) => (None, format!("nowhere (couldn't open {}: {e})", path.display())),
            }
        }
        Kind::Dev(path) => match std::fs::File::create(path) {
            Ok(f) => (Some(f), path.display().to_string()),
            Err(e) => (None, format!("nowhere (couldn't create {}: {e})", path.display())),
        },
    };

    let mut builder = env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or(option_env!("NUVX_DEFAULT_LOG").unwrap_or("warn,nuvx=info")),
    );
    match file {
        Some(f) => builder.target(env_logger::Target::Pipe(Box::new(Tee { file: f, stderr: cfg!(debug_assertions), complained: false }))),
        None => builder.target(env_logger::Target::Stderr),
    };
    let _ = builder.try_init();

    let next = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        log::error!("panic on thread {name}: {info}");
        next(info);
    }));

    let args: Vec<String> = std::env::args().collect();
    log::info!(
        "=== nuvx {} ({}/{}) ===",
        env!("CARGO_PKG_VERSION"),
        std::env::consts::OS,
        std::env::consts::ARCH
    );
    log::info!("log: {path_note}; data: {}", dir.display());
    log::info!("args: {args:?}");
}

struct Tee {
    file: std::fs::File,
    stderr: bool,
    complained: bool,
}

impl Write for Tee {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if self.stderr {
            let _ = std::io::stderr().write_all(buf);
            let _ = std::io::stderr().flush();
        }
        let line = redact(&String::from_utf8_lossy(buf));
        match self.file.write_all(line.as_bytes()).and_then(|_| self.file.flush()) {
            Ok(()) => self.complained = false,
            Err(e) => {
                if !self.complained {
                    self.complained = true;
                    let _ = std::io::stderr().write_all(format!("nuvx: log file write failed: {e}\n").as_bytes());
                }
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        let _ = self.file.flush();
        Ok(())
    }
}

/// The log file is what users attach to bug reports, so it never holds secrets: URL queries carry the
/// user's IP and signed tokens, and cookie, authorization, token and password values are blanked.
fn redact(line: &str) -> String {
    let ends = |c: char| c.is_whitespace() || matches!(c, '"' | '\'' | ')' | ']' | '>');
    let mut out = String::with_capacity(line.len());
    let mut rest = line;
    while let Some(at) = rest.find("http") {
        let (before, url) = rest.split_at(at);
        out.push_str(before);
        let end = url.find(ends).unwrap_or(url.len());
        let (whole, after) = url.split_at(end);
        match whole.find('?').filter(|_| whole.starts_with("http://") || whole.starts_with("https://")) {
            Some(q) => {
                out.push_str(&whole[..q]);
                out.push_str("?…");
            }
            None => out.push_str(whole),
        }
        rest = after;
    }
    out.push_str(rest);
    blank_secret_fields(&out)
}

fn blank_secret_fields(line: &str) -> String {
    const KEYS: [&str; 5] = ["cookie", "authorization", "token", "password", "sapisid"];
    let lower = line.to_ascii_lowercase();
    let mut out = String::with_capacity(line.len());
    let mut i = 0;
    while i < line.len() {
        let hit = KEYS.iter().find_map(|k| lower[i..].starts_with(k).then_some(k.len()));
        let Some(len) = hit.filter(|_| i == 0 || !lower.as_bytes()[i - 1].is_ascii_alphanumeric()) else {
            let c = line[i..].chars().next().unwrap();
            out.push(c);
            i += c.len_utf8();
            continue;
        };
        let mut j = i + len;
        while j < line.len() && (lower.as_bytes()[j].is_ascii_alphanumeric() || lower.as_bytes()[j] == b'_') {
            j += 1;
        }
        let sep = lower[j..].find(|c: char| c != ' ').map(|k| j + k);
        match sep.filter(|&k| matches!(lower.as_bytes()[k], b':' | b'=')) {
            Some(k) => {
                let mut v = k + 1;
                while v < line.len() && lower.as_bytes()[v] == b' ' {
                    v += 1;
                }
                if lower[v..].starts_with("bearer ") {
                    v += "bearer ".len();
                }
                let end = line[v..].find(|c: char| c.is_whitespace() || matches!(c, ';' | ',' | '&')).map_or(line.len(), |e| v + e);
                out.push_str(&line[i..v]);
                out.push('…');
                i = end;
            }
            None => {
                out.push_str(&line[i..j]);
                i = j;
            }
        }
    }
    out
}

#[cfg(test)]
mod redact_tests {
    use super::redact;

    #[test]
    fn urls_keep_host_and_path_only() {
        assert_eq!(
            redact("403 for https://rr3.googlevideo.com/videoplayback?ip=203.0.113.7&sig=AbC range=0-1\n"),
            "403 for https://rr3.googlevideo.com/videoplayback?… range=0-1\n"
        );
    }

    #[test]
    fn secrets_are_blanked() {
        let out = redact("Cookie: SAPISID=secret123; Authorization: Bearer abc token=t0k");
        assert!(!out.contains("secret123") && !out.contains("abc") && !out.contains("t0k"), "{out}");
    }

    #[test]
    fn ordinary_lines_are_untouched() {
        let line = "play gen 4: decoder ready at 0ns in 120ms — tokenizer ok, «ünïcode»\n";
        assert_eq!(redact(line), line);
    }
}

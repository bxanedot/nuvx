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
        match self.file.write_all(buf).and_then(|_| self.file.flush()) {
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

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

const LATEST: &str = "https://api.github.com/repos/bxanedot/nuvx/releases/latest";
const BY_TAG: &str = "https://api.github.com/repos/bxanedot/nuvx/releases/tags/v";
pub const RELEASES: &str = "https://github.com/bxanedot/nuvx/releases";

type Notes = Arc<Mutex<std::collections::HashMap<String, Option<String>>>>;

#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Idle,
    Checking,
    UpToDate,
    Downloading { version: String, percent: u8 },
    Ready { version: String, installer: PathBuf },
    Failed(String),
}

#[derive(Clone)]
pub struct Updater {
    state: Arc<Mutex<State>>,
    dir: PathBuf,
    wake: Arc<dyn Fn() + Send + Sync>,
    notes: Notes,
}

pub fn current_version() -> String {
    std::env::var("NUVX_UPDATE_AS").unwrap_or_else(|_| env!("CARGO_PKG_VERSION").to_owned())
}

pub fn installed() -> bool {
    if std::env::var_os("NUVX_UPDATE_FORCE").is_some() {
        return true;
    }
    let Ok(exe) = std::env::current_exe() else { return false };
    if cfg!(target_os = "macos") {
        return bundle_of(&exe).is_some() && portable_dir().is_none();
    }
    let marker = if cfg!(windows) { "unins000.exe" } else { "install.txt" };
    exe.parent().is_some_and(|d| d.join(marker).exists())
}

fn bundle_of(exe: &Path) -> Option<&Path> {
    exe.ancestors().nth(3).filter(|a| a.extension().is_some_and(|x| x == "app"))
}

fn asset_name(version: &str) -> String {
    asset_name_for(std::env::consts::OS, version)
}

fn asset_name_for(os: &str, version: &str) -> String {
    match os {
        "linux" => format!("nuvx-setup-{version}-linux-universal.tar.gz"),
        "macos" => format!("nuvx-setup-{version}-macos-universal.dmg"),
        _ => format!("nuvx-setup-{version}.exe"),
    }
}

pub fn portable_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    if let Some(app) = bundle_of(&exe) {
        if app.to_string_lossy().contains("/AppTranslocation/") {
            return None;
        }
        if !app.join("Contents/Resources/portable.txt").exists() {
            return None;
        }
        return app.parent().map(Path::to_path_buf);
    }
    let dir = exe.parent()?.to_path_buf();
    dir.join("portable.txt").exists().then_some(dir)
}

pub fn clean_portable() {
    if portable_dir().is_some()
        && let Ok(exe) = std::env::current_exe()
    {
        let _ = std::fs::remove_file(exe.with_extension("exe.old"));
    }
}

pub fn newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| v.trim_start_matches('v').split('.').map(|p| p.parse::<u64>().unwrap_or(0)).collect::<Vec<_>>();
    let (a, b) = (parse(a), parse(b));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    false
}

impl Updater {
    pub fn new(data_dir: &Path, wake: impl Fn() + Send + Sync + 'static) -> Self {
        Self { state: Arc::new(Mutex::new(State::Idle)), dir: data_dir.join("updates"), wake: Arc::new(wake), notes: Default::default() }
    }

    pub fn state(&self) -> State {
        self.state.lock().unwrap().clone()
    }

    fn set(&self, s: State) {
        *self.state.lock().unwrap() = s;
        (self.wake)();
    }

    pub fn notes(&self, version: &str) -> Option<String> {
        let version = version.trim_start_matches('v').to_owned();
        {
            let mut notes = self.notes.lock().unwrap();
            if let Some(n) = notes.get(&version) {
                return n.clone();
            }
            notes.insert(version.clone(), None);
        }
        let this = self.clone();
        std::thread::spawn(move || {
            let fetch = async {
                let http = client()?;
                let release: serde_json::Value = http.get(format!("{BY_TAG}{version}")).send().await?.error_for_status()?.json().await?;
                anyhow::Ok(release["body"].as_str().map(str::to_owned))
            };
            let body = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                Ok(rt) => rt.block_on(fetch).unwrap_or_else(|e| {
                    log::warn!("update: notes for {version}: {e:#}");
                    None
                }),
                Err(_) => None,
            };
            if body.is_some() {
                this.notes.lock().unwrap().insert(version.clone(), body);
                (this.wake)();
            }
        });
        None
    }

    pub fn check(&self) {
        if matches!(self.state(), State::Checking | State::Downloading { .. } | State::Ready { .. }) {
            return;
        }
        self.set(State::Checking);
        let this = self.clone();
        std::thread::spawn(move || {
            let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                Ok(rt) => rt,
                Err(e) => return this.set(State::Failed(e.to_string())),
            };
            match rt.block_on(this.run()) {
                Ok(s) => this.set(s),
                Err(e) => {
                    log::warn!("update check failed: {e:#}");
                    this.set(State::Failed(format!("{e:#}")));
                }
            }
        });
    }

    async fn run(&self) -> anyhow::Result<State> {
        let http = client()?;
        let feed = std::env::var("NUVX_UPDATE_FEED").unwrap_or_else(|_| LATEST.to_owned());
        let release: serde_json::Value = http.get(feed).send().await?.error_for_status()?.json().await?;
        let tag = release["tag_name"].as_str().unwrap_or_default();
        if let Some(body) = release["body"].as_str() {
            self.notes.lock().unwrap().insert(tag.trim_start_matches('v').to_owned(), Some(body.to_owned()));
        }
        let current = current_version();
        if !newer(tag, &current) {
            log::info!("update: {current} is current (latest {tag})");
            return Ok(State::UpToDate);
        }
        let version = tag.trim_start_matches('v').to_owned();
        let want = asset_name(&version);
        let Some(asset) = release["assets"].as_array().and_then(|a| a.iter().find(|x| x["name"] == want.as_str())) else {
            log::info!("update: release {tag} has no {want} yet");
            return Ok(State::UpToDate);
        };
        let url = asset["browser_download_url"].as_str().ok_or_else(|| anyhow::anyhow!("asset has no URL"))?;
        let size = asset["size"].as_u64().unwrap_or(0);
        std::fs::create_dir_all(&self.dir)?;
        let path = self.dir.join(&want);
        if std::fs::metadata(&path).map(|m| m.len()).ok() != Some(size) || size == 0 {
            log::info!("update: downloading {version} ({size} bytes)");
            self.set(State::Downloading { version: version.clone(), percent: 0 });
            let mut resp = http.get(url).send().await?.error_for_status()?;
            let part = path.with_extension("part");
            let mut file = std::io::BufWriter::new(std::fs::File::create(&part)?);
            let (mut got, mut shown) = (0u64, 0u8);
            while let Some(chunk) = resp.chunk().await? {
                std::io::Write::write_all(&mut file, &chunk)?;
                got += chunk.len() as u64;
                let percent = (got * 100).checked_div(size).map_or(0, |p| p.min(100) as u8);
                if percent != shown {
                    shown = percent;
                    self.set(State::Downloading { version: version.clone(), percent });
                }
            }
            std::io::Write::flush(&mut file)?;
            drop(file);
            if size > 0 && got != size {
                let _ = std::fs::remove_file(&part);
                anyhow::bail!("download incomplete ({got} of {size} bytes)");
            }
            std::fs::rename(&part, &path)?;
        }
        if let Ok(rd) = std::fs::read_dir(&self.dir) {
            for e in rd.flatten() {
                let p = e.path();
                if p != path {
                    let _ = if p.is_dir() { std::fs::remove_dir_all(&p) } else { std::fs::remove_file(&p) };
                }
            }
        }
        let unpacked = self.dir.join(format!("nuvx-{version}"));
        let ready = if cfg!(target_os = "macos") {
            open_dmg(&path, &unpacked)?
        } else if cfg!(unix) {
            unzip_exe(&path, &unpacked)?
        } else {
            path
        };
        log::info!("update: {version} ready at {}", ready.display());
        Ok(State::Ready { version, installer: ready })
    }

    pub fn install(&self, relaunch: bool) {
        let State::Ready { installer, .. } = self.state() else { return };
        let exe = std::env::current_exe().unwrap_or_default();
        #[cfg(target_os = "macos")]
        if let (Some(new), Some(app)) = (bundle_of(&installer), bundle_of(&exe)) {
            return replace_bundle(new, app, relaunch);
        }
        #[cfg(unix)]
        replace_binary(&installer, &exe, relaunch);
        #[cfg(windows)]
        self.run_installer(&installer, &exe, relaunch);
    }

    #[cfg(windows)]
    fn run_installer(&self, installer: &Path, exe: &Path, relaunch: bool) {
        let previous_portable_dir = portable_dir();
        let program_files = std::env::var_os("ProgramFiles").map(PathBuf::from);
        let scope = if previous_portable_dir.is_some() {
            "/CURRENTUSER"
        } else if program_files.is_some_and(|p| exe.starts_with(p)) {
            "/ALLUSERS"
        } else {
            "/CURRENTUSER"
        };
        let install_dir = previous_portable_dir
            .map(|dir| format!(" /DIR=\"{}\"", dir.display()))
            .unwrap_or_default();
        let mut script = format!(
            "ping -n 3 127.0.0.1 >NUL & \"{}\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART {scope}{install_dir}",
            installer.display(),
        );
        if relaunch {
            script += &format!(" & start \"\" \"{}\" {}", exe.display(), crate::deeplink::RELAUNCH_FLAG);
        }
        if std::env::var_os("NUVX_UPDATE_DRYRUN").is_some() {
            log::info!("update (dry run): cmd /C {script}");
            return;
        }
        log::info!("update: installing {}", installer.display());
        run_detached(&script);
    }
}

#[cfg(unix)]
fn replace_binary(new: &Path, exe: &Path, relaunch: bool) {
    use std::os::unix::fs::PermissionsExt;
    let tmp = exe.with_file_name(".nuvx.new");
    if std::env::var_os("NUVX_UPDATE_DRYRUN").is_some() {
        log::info!("update (dry run): {} over {}", new.display(), exe.display());
        return;
    }
    let done = std::fs::copy(new, &tmp)
        .and_then(|_| std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o755)))
        .and_then(|_| std::fs::rename(&tmp, exe));
    if let Err(e) = done {
        let _ = std::fs::remove_file(&tmp);
        return log::warn!("update: couldn't replace {}: {e}", exe.display());
    }
    log::info!("update: replaced {}", exe.display());
    if relaunch {
        let quoted = exe.display().to_string().replace('\'', r"'\''");
        run_detached(&format!("sleep 2; exec '{quoted}'"));
    }
}

#[cfg(target_os = "macos")]
fn replace_bundle(new: &Path, app: &Path, relaunch: bool) {
    let parent = app.parent().unwrap_or(Path::new("/"));
    let (tmp, old) = (parent.join(".nuvx.app.new"), parent.join(".nuvx.app.old"));
    if std::env::var_os("NUVX_UPDATE_DRYRUN").is_some() {
        log::info!("update (dry run): {} in place of {}", new.display(), app.display());
        return;
    }
    let _ = std::fs::remove_dir_all(&tmp);
    let _ = std::fs::remove_dir_all(&old);
    let copied = std::process::Command::new("ditto").arg(new).arg(&tmp).status().is_ok_and(|s| s.success());
    if !copied {
        let _ = std::fs::remove_dir_all(&tmp);
        return log::warn!("update: couldn't copy {} beside {}", new.display(), app.display());
    }
    if let Err(e) = std::fs::rename(app, &old) {
        let _ = std::fs::remove_dir_all(&tmp);
        return log::warn!("update: couldn't move {} aside: {e}", app.display());
    }
    if let Err(e) = std::fs::rename(&tmp, app) {
        let _ = std::fs::rename(&old, app);
        let _ = std::fs::remove_dir_all(&tmp);
        return log::warn!("update: couldn't put the new version in place: {e}");
    }
    let _ = std::fs::remove_dir_all(&old);
    log::info!("update: replaced {}", app.display());
    if relaunch {
        let quoted = app.display().to_string().replace('\'', r"'\''");
        run_detached(&format!("sleep 2; open '{quoted}'"));
    }
}

fn open_dmg(dmg: &Path, dir: &Path) -> anyhow::Result<PathBuf> {
    let _ = std::fs::remove_dir_all(dir);
    let mnt = dir.join("mnt");
    std::fs::create_dir_all(&mnt)?;
    let attached = std::process::Command::new("hdiutil").args(["attach", "-nobrowse", "-readonly", "-noautoopen", "-quiet", "-mountpoint"]).arg(&mnt).arg(dmg).status()?;
    anyhow::ensure!(attached.success(), "couldn't open {}", dmg.display());
    let copied = std::process::Command::new("ditto").arg(mnt.join("nuvx.app")).arg(dir.join("nuvx.app")).status();
    let _ = std::process::Command::new("hdiutil").args(["detach", "-quiet"]).arg(&mnt).status();
    let exe = dir.join("nuvx.app/Contents/MacOS/nuvx");
    anyhow::ensure!(copied.is_ok_and(|s| s.success()) && exe.exists(), "couldn't copy the app out of {}", dmg.display());
    Ok(exe)
}

fn unzip_exe(zip: &Path, dir: &Path) -> anyhow::Result<PathBuf> {
    let _ = std::fs::remove_dir_all(dir);
    std::fs::create_dir_all(dir)?;
    #[cfg(windows)]
    let mut cmd = {
        let system = std::env::var_os("SystemRoot").map_or_else(|| PathBuf::from(r"C:\Windows"), PathBuf::from);
        std::process::Command::new(system.join(r"System32\tar.exe"))
    };
    #[cfg(not(windows))]
    let mut cmd = std::process::Command::new("tar");
    cmd.arg("-xf").arg(zip).arg("-C").arg(dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let status = cmd.status()?;
    #[cfg(windows)]
    let exe = dir.join("nuvx.exe");
    #[cfg(target_os = "macos")]
    let exe = dir.join("nuvx.app/Contents/MacOS/nuvx");
    #[cfg(target_os = "linux")]
    let exe = dir
        .join("nuvx")
        .join(format!("nuvx-{}", std::env::consts::ARCH));
    anyhow::ensure!(status.success() && exe.exists(), "couldn't unpack {}", zip.display());
    Ok(exe)
}

fn run_detached(script: &str) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        const DETACHED_PROCESS: u32 = 0x0000_0008;
        if let Err(e) = std::process::Command::new("cmd").arg("/C").raw_arg(script).creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS).spawn() {
            log::warn!("update: couldn't run the update: {e}");
        }
    }
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        if let Err(e) = std::process::Command::new("sh").arg("-c").arg(script).process_group(0).spawn() {
            log::warn!("update: couldn't run the update: {e}");
        }
    }
}

fn client() -> anyhow::Result<reqwest::Client> {
    Ok(crate::proxy::apply(reqwest::Client::builder()).user_agent(concat!("nuvx/", env!("CARGO_PKG_VERSION"))).connect_timeout(std::time::Duration::from_secs(10)).read_timeout(std::time::Duration::from_secs(30)).build()?)
}

#[cfg(test)]
mod tests {
    use super::{asset_name_for, newer};

    #[test]
    fn universal_installer_names_match_each_platform() {
        assert_eq!(
            asset_name_for("windows", "0.8.0"),
            "nuvx-setup-0.8.0.exe"
        );
        assert_eq!(
            asset_name_for("macos", "0.8.0"),
            "nuvx-setup-0.8.0-macos-universal.dmg"
        );
        assert_eq!(
            asset_name_for("linux", "0.8.0"),
            "nuvx-setup-0.8.0-linux-universal.tar.gz"
        );
    }

    #[test]
    fn version_order() {
        assert!(newer("v0.2.8", "0.2.7"));
        assert!(newer("v0.10.0", "0.9.9"));
        assert!(newer("1.0", "0.99.99"));
        assert!(!newer("v0.2.7", "0.2.7"));
        assert!(!newer("v0.2.6", "0.2.7"));
        assert!(!newer("", "0.2.7"));
    }
}

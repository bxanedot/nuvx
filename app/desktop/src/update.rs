use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

const RELEASE_LIST: &str = "https://api.github.com/repos/lylicht/nuvx/releases?per_page=100";
const BY_TAG: &str = "https://api.github.com/repos/lylicht/nuvx/releases/tags/";
pub const RELEASES: &str = "https://github.com/lylicht/nuvx/releases";

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
    nightly: Arc<AtomicBool>,
}

pub fn current_version() -> String {
    std::env::var("NUVX_UPDATE_AS").unwrap_or_else(|_| option_env!("NUVX_UPDATE_VERSION").unwrap_or(env!("CARGO_PKG_VERSION")).to_owned())
}

pub fn current_release_tag() -> String {
    std::env::var("NUVX_RELEASE_TAG").unwrap_or_else(|_| option_env!("NUVX_RELEASE_TAG").map_or_else(|| format!("v{}", env!("CARGO_PKG_VERSION")), str::to_owned))
}

fn current_build_time() -> String {
    std::env::var("NUVX_BUILD_TIME").unwrap_or_else(|_| option_env!("NUVX_BUILD_TIME").unwrap_or_default().to_owned())
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
    asset_name_for(std::env::consts::OS, std::env::consts::ARCH, version)
}

fn asset_name_for(os: &str, _arch: &str, version: &str) -> String {
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

pub use nuvx_core::release::newer;
use nuvx_core::release::{compare_release_order, is_old_build, release_is_newer};

impl Updater {
    pub fn new(data_dir: &Path, wake: impl Fn() + Send + Sync + 'static) -> Self {
        Self { state: Arc::new(Mutex::new(State::Idle)), dir: data_dir.join("updates"), wake: Arc::new(wake), notes: Default::default(), nightly: Default::default() }
    }

    /// Whether nightly builds may be offered. Off unless the user turns it on.
    pub fn set_nightly(&self, on: bool) {
        self.nightly.store(on, Ordering::Relaxed);
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
        let tag = version.split_once("-nightly.").map_or_else(|| format!("v{version}"), |(_, suffix)| format!("nightly.{suffix}"));
        std::thread::spawn(move || {
            let fetch = async {
                let http = client()?;
                let release: serde_json::Value = http.get(format!("{BY_TAG}{tag}")).send().await?.error_for_status()?.json().await?;
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
        let feed = std::env::var("NUVX_UPDATE_FEED").unwrap_or_else(|_| RELEASE_LIST.to_owned());
        let response: serde_json::Value = http.get(feed).send().await?.error_for_status()?.json().await?;
        let releases = response.as_array().cloned().unwrap_or_else(|| vec![response]);
        let current = current_version();
        let current_tag = current_release_tag();
        let current_time = current_build_time();
        let nightly = self.nightly.load(Ordering::Relaxed);
        let candidate = releases
            .iter()
            .filter_map(|release| {
                if release["draft"].as_bool() != Some(false) {
                    return None;
                }
                let tag = release["tag_name"].as_str()?;
                if is_old_build(release["name"].as_str(), tag, release["prerelease"].as_bool() == Some(true)) {
                    return None;
                }
                let version = release["name"].as_str().and_then(|name| name.strip_prefix("nuvx ")).unwrap_or_else(|| tag.trim_start_matches('v'));
                let published_at = release["published_at"].as_str()?;
                if !release_is_newer(version, tag, published_at, &current, &current_tag, &current_time, nightly) {
                    return None;
                }
                let want = asset_name(tag.trim_start_matches('v'));
                let asset = release["assets"].as_array()?.iter().find(|asset| asset["name"] == want)?;
                Some((published_at, tag, version, want, release, asset))
            })
            .max_by(|a, b| compare_release_order(a.2, a.1, a.0, b.2, b.1, b.0));
        let Some((_, tag, version, want, release, asset)) = candidate else {
            log::info!("update: {current} is current");
            return Ok(State::UpToDate);
        };
        if let Some(body) = release["body"].as_str() {
            self.notes.lock().unwrap().insert(version.to_owned(), Some(body.to_owned()));
        }
        let version = version.to_owned();
        let want = want.to_owned();
        let url = asset["browser_download_url"].as_str().ok_or_else(|| anyhow::anyhow!("asset has no URL"))?;
        let size = asset["size"].as_u64().unwrap_or(0);
        std::fs::create_dir_all(&self.dir)?;
        let path = self.dir.join(&want);
        if std::fs::metadata(&path).map(|m| m.len()).ok() != Some(size) || size == 0 {
            log::info!("update: downloading {version} from {tag} ({size} bytes)");
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
        if let Some(want) = asset["digest"].as_str().and_then(|d| d.strip_prefix("sha256:")) {
            if !sha256_matches(&path, want)? {
                let _ = std::fs::remove_file(&path);
                anyhow::bail!("download of {version} is damaged (SHA-256 mismatch)");
            }
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

// GitHub lists each asset's SHA-256 as "digest"; older assets have none, and then only the size is checked.
fn sha256_matches(path: &Path, want_hex: &str) -> anyhow::Result<bool> {
    use sha2::Digest;
    let mut hasher = sha2::Sha256::new();
    std::io::copy(&mut std::fs::File::open(path)?, &mut hasher)?;
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect::<String>().eq_ignore_ascii_case(want_hex))
}

fn client() -> anyhow::Result<reqwest::Client> {
    Ok(crate::proxy::apply(reqwest::Client::builder()).user_agent(concat!("nuvx/", env!("CARGO_PKG_VERSION"))).connect_timeout(std::time::Duration::from_secs(10)).read_timeout(std::time::Duration::from_secs(30)).build()?)
}

#[cfg(test)]
mod tests {
    use super::{asset_name_for, compare_release_order, is_old_build, newer, sha256_matches};

    #[test]
    fn sha256_check_accepts_the_right_file_only() {
        let path = std::env::temp_dir().join("nuvx-sha256-check");
        std::fs::write(&path, b"abc").unwrap();
        assert!(sha256_matches(&path, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD").unwrap());
        assert!(!sha256_matches(&path, "0000000000000000000000000000000000000000000000000000000000000000").unwrap());
        let _ = std::fs::remove_file(&path);
    }

    fn release_is_newer(a: &str, b: &str, c: &str, d: &str, e: &str, f: &str) -> bool {
        super::release_is_newer(a, b, c, d, e, f, e.starts_with("nightly."))
    }

    #[test]
    fn installer_names_match_each_platform_variant() {
        assert_eq!(
            asset_name_for("windows", "x86_64", "0.9.0"),
            "nuvx-setup-0.9.0.exe"
        );
        assert_eq!(
            asset_name_for("macos", "aarch64", "0.9.0"),
            "nuvx-setup-0.9.0-macos-universal.dmg"
        );
        assert_eq!(
            asset_name_for("linux", "x86_64", "0.9.0"),
            "nuvx-setup-0.9.0-linux-universal.tar.gz"
        );
        assert_eq!(
            asset_name_for("macos", "x86_64", "0.9.0"),
            "nuvx-setup-0.9.0-macos-universal.dmg"
        );
        assert_eq!(
            asset_name_for("windows", "x86_64", "nightly.45.1"),
            "nuvx-setup-nightly.45.1.exe"
        );
        assert_eq!(
            asset_name_for("linux", "aarch64", "nightly.45.1"),
            "nuvx-setup-nightly.45.1-linux-universal.tar.gz"
        );
        assert_eq!(
            asset_name_for("macos", "aarch64", "nightly.45.1"),
            "nuvx-setup-nightly.45.1-macos-universal.dmg"
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

    #[test]
    fn release_selection_respects_stable_and_nightly_channels() {
        let stable_time = "2026-11-13T11:00:00Z";
        let nightly_time = "2026-11-13T12:00:00Z";
        let later_nightly_time = "2026-11-13T13:00:00Z";
        assert!(!release_is_newer("0.9.0-nightly.42.1", "nightly.42.1", nightly_time, "0.9.0", "v0.9.0", stable_time));
        assert!(release_is_newer("0.9.0", "v0.9.0", later_nightly_time, "0.9.0-nightly.42.1", "nightly.42.1", nightly_time), "the stable these nightlies led to");
        assert!(!release_is_newer("0.9.0", "v0.9.0", stable_time, "0.9.0-nightly.43.1", "nightly.43.1", later_nightly_time));
        assert!(release_is_newer("0.9.0", "v0.9.0", later_nightly_time, "0.9.0-nightly.43.1", "nightly.43.1", nightly_time));
        assert!(release_is_newer("0.9.0-nightly.43.1", "nightly.43.1", stable_time, "0.9.0-nightly.42.1", "nightly.42.1", later_nightly_time));
        assert!(!release_is_newer("0.9.0-nightly.42.1", "nightly.42.1", later_nightly_time, "0.9.0-nightly.43.1", "nightly.43.1", nightly_time));
        assert!(!release_is_newer("0.9.0-nightly.42.1", "nightly.42.1", nightly_time, "0.9.0", "v0.9.0", later_nightly_time));
        assert!(!release_is_newer("0.9.0", "v0.9.0", later_nightly_time, "0.9.0", "v0.9.0", nightly_time));
        assert!(!release_is_newer("0.10.0-nightly.1.1", "nightly.1.1", later_nightly_time, "0.9.0", "v0.9.0", stable_time));
        assert!(release_is_newer("1.0.0", "v1.0.0", stable_time, "0.9.0-nightly.43.1", "nightly.43.1", later_nightly_time));
        assert!(release_is_newer("0.10.0", "v0.10.0", stable_time, "0.9.0", "v0.9.0", later_nightly_time));
        assert_eq!(compare_release_order("1.0.0", "v1.0.0", stable_time, "1.0.0-nightly.44.1", "nightly.44.1", later_nightly_time), std::cmp::Ordering::Greater);
        assert_eq!(compare_release_order("0.9.0-nightly.44.1", "nightly.44.1", stable_time, "0.9.0-nightly.43.1", "nightly.43.1", later_nightly_time), std::cmp::Ordering::Greater);
    }

    #[test]
    fn nightlies_are_offered_only_when_switched_on() {
        let built = "2026-11-17T10:00:00Z";
        let published = "2026-11-17T12:00:00Z";
        let rel = super::release_is_newer;
        assert!(!rel("1.0.0-nightly.5.1", "nightly.5.1", published, "1.0.0", "v1.0.0", built, false));
        assert!(rel("1.0.0-nightly.5.1", "nightly.5.1", published, "1.0.0", "v1.0.0", built, true));
        assert!(!rel("1.0.0-nightly.6.1", "nightly.6.1", published, "1.0.0-nightly.5.1", "nightly.5.1", built, false));
        assert!(rel("1.0.0-nightly.6.1", "nightly.6.1", published, "1.0.0-nightly.5.1", "nightly.5.1", built, true));
        // A nightly install moves to the stable build of the same version, nightlies on or off.
        assert!(rel("1.0.0", "v1.0.0", published, "1.0.0-nightly.5.1", "nightly.5.1", built, false));
        assert!(rel("1.0.0", "v1.0.0", published, "1.0.0-nightly.5.1", "nightly.5.1", built, true));
        assert!(!rel("1.0.0", "v1.0.0", "2026-11-17T09:00:00Z", "1.0.0-nightly.5.1", "nightly.5.1", built, false));
    }

    #[test]
    fn old_builds_and_non_nightly_prereleases_are_never_offered() {
        assert!(is_old_build(Some("nuvx 1.1.0 (old build)"), "v1.1.0", true));
        assert!(is_old_build(Some("Old"), "v0.9.0", false));
        assert!(is_old_build(Some("nuvx 1.1.0-nightly.170.1 (old build)"), "nightly.170.1", true));
        assert!(is_old_build(Some("nuvx 1.2.0"), "v1.2.0", true));
        assert!(!is_old_build(Some("nuvx 1.0.0"), "v1.0.0", false));
        assert!(!is_old_build(Some("nuvx 1.0.0-nightly.7.1"), "nightly.7.1", true));
        assert!(!is_old_build(Some("Golden hour"), "v1.0.1", false));
        assert!(!is_old_build(None, "v1.0.0", false));
    }

    // Mirrors Updater::run's selection: keep the newer releases, take the best by compare_release_order.
    fn offer<'a>(feed: &[(&'a str, &'a str, &'a str)], installed: (&str, &str, &str), nightly: bool) -> Option<&'a str> {
        feed.iter()
            .filter(|(v, tag, time)| super::release_is_newer(v, tag, time, installed.0, installed.1, installed.2, nightly))
            .max_by(|a, b| compare_release_order(a.0, a.1, a.2, b.0, b.1, b.2))
            .map(|r| r.1)
    }

    #[test]
    fn stable_then_nightlies_then_a_new_stable() {
        let s100 = ("1.0.0", "v1.0.0", "2026-11-10T00:00:00Z");
        let n121 = ("1.0.0-nightly.121.1", "nightly.121.1", "2026-11-11T00:00:00Z");
        let n122 = ("1.0.0-nightly.122.1", "nightly.122.1", "2026-11-12T00:00:00Z");
        let s101 = ("1.0.1", "v1.0.1", "2026-11-13T00:00:00Z");
        let s120 = ("1.2.0", "v1.2.0", "2026-11-14T00:00:00Z");
        assert_eq!(offer(&[s100, n121], s100, true), Some("nightly.121.1"));
        assert_eq!(offer(&[s100, n121, n122], n121, true), Some("nightly.122.1"));
        assert_eq!(offer(&[s100, n121, n122], n122, true), None);
        assert_eq!(offer(&[s100, n121, n122], s100, false), None);
        assert_eq!(offer(&[s100, n121, n122], n121, false), None);
        for nightly in [true, false] {
            assert_eq!(offer(&[s100, n121, n122, s101, s120], n122, nightly), Some("v1.2.0"));
        }
        assert_eq!(offer(&[n122, s101], s100, true), Some("v1.0.1"));
    }
}

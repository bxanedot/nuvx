//! The window's material: the app's own background (Dynamic; saved as "solid"), or the system's own material
//! showing through the window, as BitChord does. The same three choices everywhere, each
//! system's own: Windows 11's Mica and Acrylic, macOS's Window and Sidebar vibrancy,
//! and on Linux the desktop tinted or showing through (the compositor decides any blur).
//! The window is made see-through once when it opens, so a change applies at once.

use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU8, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Material {
    Default,
    Mica,
    Acrylic,
}

impl Material {
    pub const ALL: [Material; 3] = [Material::Default, Material::Mica, Material::Acrylic];

    /// What this system calls it.
    pub fn name(self) -> &'static str {
        match self {
            Material::Default => "Dynamic",
            Material::Mica if cfg!(target_os = "macos") => "Window",
            Material::Acrylic if cfg!(target_os = "macos") => "Sidebar",
            Material::Mica if cfg!(target_os = "linux") => "Tinted",
            Material::Acrylic if cfg!(target_os = "linux") => "Translucent",
            Material::Mica => "Mica",
            Material::Acrylic => "Acrylic",
        }
    }

    /// How it's saved, the same on every system.
    fn key(self) -> &'static str {
        match self {
            Material::Default => "solid",
            Material::Mica => "mica",
            Material::Acrylic => "acrylic",
        }
    }

    /// Acrylic (Sidebar on macOS) where the system blurs; on Linux, where that's up to the
    /// compositor, the app's own background.
    const fn initial() -> Material {
        if cfg!(any(windows, target_os = "macos")) { Material::Acrylic } else { Material::Default }
    }
}

/// Whether this platform can show a material: the window is see-through from the start there.
pub const SUPPORTED: bool = cfg!(any(windows, target_os = "linux", target_os = "macos"));

static CHOSEN: AtomicU8 = AtomicU8::new(Material::initial() as u8);
/// Whether the system took the material (Windows 10 has no Mica or Acrylic: then Dynamic shows).
static APPLIED: AtomicBool = AtomicBool::new(cfg!(target_os = "linux"));
static WINDOW: AtomicPtr<std::ffi::c_void> = AtomicPtr::new(std::ptr::null_mut());
/// The dark-mode flag last handed to the window: 0 unset, 1 light, 2 dark.
static DARK: AtomicU8 = AtomicU8::new(0);

/// Reads the saved choice. Call before the window opens.
pub fn init(data_dir: &Path) -> Material {
    let m = if SUPPORTED { load(data_dir) } else { Material::Default };
    CHOSEN.store(m as u8, Ordering::Relaxed);
    m
}

/// The material chosen.
pub fn active() -> Material {
    Material::ALL[usize::from(CHOSEN.load(Ordering::Relaxed)).min(2)]
}

/// Whether the material shows now (the app paints its background translucent).
pub fn see_through() -> bool {
    active() != Material::Default && APPLIED.load(Ordering::Relaxed)
}

pub fn load(data_dir: &Path) -> Material {
    match std::fs::read_to_string(data_dir.join("material")).unwrap_or_default().trim() {
        "mica" => Material::Mica,
        "acrylic" => Material::Acrylic,
        "solid" => Material::Default,
        _ => Material::initial(),
    }
}

pub fn save(data_dir: &Path, m: Material) -> std::io::Result<()> {
    std::fs::write(data_dir.join("material"), m.key())
}

/// Switches the material now.
pub fn set(m: Material) {
    CHOSEN.store(m as u8, Ordering::Relaxed);
    apply();
}

/// Hands the window's `NSView` over for the material (once, when it opens).
#[cfg(target_os = "macos")]
pub fn set_view(view: *mut std::ffi::c_void) {
    WINDOW.store(view, Ordering::Relaxed);
    apply();
}

/// The veil over the material, heavier for Linux's tinted one (no blur behind it there).
pub fn veil(light: bool) -> egui::Color32 {
    let tinted = cfg!(target_os = "linux") && active() == Material::Mica;
    match (light, tinted) {
        (true, false) => egui::Color32::from_white_alpha(110),
        (true, true) => egui::Color32::from_white_alpha(200),
        (false, false) => egui::Color32::from_black_alpha(90),
        (false, true) => egui::Color32::from_black_alpha(185),
    }
}

/// Hands the window to DWM for the material (once, when it opens).
pub fn set_hwnd(hwnd: *mut std::ffi::c_void) {
    WINDOW.store(hwnd, Ordering::Relaxed);
    apply();
}

fn apply() {
    #[cfg(target_os = "macos")]
    {
        use raw_window_handle::{AppKitWindowHandle, HandleError, HasWindowHandle, RawWindowHandle, WindowHandle};
        use window_vibrancy::{NSVisualEffectMaterial, NSVisualEffectState, apply_vibrancy, clear_vibrancy};
        struct View(std::ptr::NonNull<std::ffi::c_void>);
        impl HasWindowHandle for View {
            fn window_handle(&self) -> Result<WindowHandle<'_>, HandleError> {
                // SAFETY: the app's own view, alive as long as the window.
                Ok(unsafe { WindowHandle::borrow_raw(RawWindowHandle::AppKit(AppKitWindowHandle::new(self.0))) })
            }
        }
        let Some(view) = std::ptr::NonNull::new(WINDOW.load(Ordering::Relaxed)).map(View) else { return };
        let _ = clear_vibrancy(&view);
        let kind = match active() {
            Material::Default => return,
            Material::Mica => NSVisualEffectMaterial::UnderWindowBackground,
            Material::Acrylic => NSVisualEffectMaterial::Sidebar,
        };
        let ok = apply_vibrancy(&view, kind, Some(NSVisualEffectState::Active), None);
        if let Err(e) = &ok {
            log::warn!("material: {:?} unavailable ({e})", active());
        }
        APPLIED.store(ok.is_ok(), Ordering::Relaxed);
    }
    #[cfg(windows)]
    {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::Graphics::Dwm::{DWMSBT_MAINWINDOW, DWMSBT_NONE, DWMSBT_TRANSIENTWINDOW, DWMWA_SYSTEMBACKDROP_TYPE, DwmSetWindowAttribute};
        let hwnd = WINDOW.load(Ordering::Relaxed);
        if hwnd.is_null() {
            return;
        }
        let kind = match active() {
            Material::Default => DWMSBT_NONE,
            Material::Mica => DWMSBT_MAINWINDOW,
            Material::Acrylic => DWMSBT_TRANSIENTWINDOW,
        };
        // SAFETY: the app's own window, on the thread that made it; the attribute is a
        // 4-byte value that outlives the call.
        let ok = unsafe { DwmSetWindowAttribute(HWND(hwnd), DWMWA_SYSTEMBACKDROP_TYPE, (&raw const kind).cast(), 4) };
        if let Err(e) = &ok {
            log::warn!("material: {:?} unavailable ({e}); Windows 11 22H2 or later has it", active());
        }
        APPLIED.store(ok.is_ok(), Ordering::Relaxed);
    }
}

/// Matches the material to the app's theme: dark Mica behind dark pages, light behind light.
pub fn set_dark(dark: bool) {
    let hwnd = WINDOW.load(Ordering::Relaxed);
    if hwnd.is_null() || DARK.swap(if dark { 2 } else { 1 }, Ordering::Relaxed) == if dark { 2 } else { 1 } {
        return;
    }
    #[cfg(windows)]
    {
        use windows::Win32::Foundation::HWND;
        use windows::Win32::Graphics::Dwm::{DWMWA_USE_IMMERSIVE_DARK_MODE, DwmSetWindowAttribute};
        let v = i32::from(dark);
        // SAFETY: as in `apply`.
        unsafe {
            let _ = DwmSetWindowAttribute(HWND(hwnd), DWMWA_USE_IMMERSIVE_DARK_MODE, (&raw const v).cast(), 4);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saved_choice_round_trips() {
        let dir = std::env::temp_dir().join(format!("nuvx-material-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(load(&dir), Material::initial());
        for m in Material::ALL {
            save(&dir, m).unwrap();
            assert_eq!(load(&dir), m);
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}

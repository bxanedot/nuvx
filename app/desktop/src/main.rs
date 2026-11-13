#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod appicon;
mod apple;
mod artist_signature;
mod fullscreen;
mod proxy;
mod renderer;
mod art;
mod audio;
mod backend;
mod bidi;
mod deeplink;
mod discord;
mod dash;
mod downloads;
mod eq;
mod hires;
mod images;
mod jam;
mod innertube;
mod jsc;
mod lastfm;
mod logging;
mod login;
mod lyrics;
mod media;
mod model;
mod motion;
mod tidal;
mod opus;
mod preset;
mod radio;
mod material;
mod shader;
mod sponsorblock;
mod stream;
mod theme;
mod transfer;
mod translate;
mod tray;
mod turnstile;
mod ui;
mod update;
mod webmusic;
mod ytdlp;
mod ytm;
mod vault;
mod ytmapi;

use std::sync::{Arc, OnceLock};

use crossbeam_channel::unbounded;

pub const DEFAULT_SIZE: [f32; 2] = [1200.0, 780.0];
pub const MIN_SIZE: [f32; 2] = [760.0, 480.0];

fn main() -> eframe::Result {
    let mut args = std::env::args_os().skip(1);
    if let (Some(flag), Some(dir)) = (args.next(), args.next()) {
        proxy::init(std::path::Path::new(""));
        if flag == login::FLAG {
            logging::init_child(&data_dir(), "login");
            login::child_main(login::Site::Google, dir.into());
            return Ok(());
        }
        if flag == login::SPOTIFY_FLAG {
            logging::init_child(&data_dir(), "spotify");
            login::child_main(login::Site::Spotify, dir.into());
            return Ok(());
        }
        if flag == turnstile::FLAG {
            logging::init_child(&data_dir(), "turnstile");
            turnstile::child_main(dir.into());
            return Ok(());
        }
    }
    let mut show_requested = false;
    if let Some(code) = deeplink::from_args() {
        if deeplink::forward_to_running(&code) {
            return Ok(());
        }
        deeplink::push(code);
    } else if deeplink::forward_show() {
        return Ok(());
    } else {
        show_requested = std::env::args_os().skip(1).any(|a| a == deeplink::SHOW_FLAG);
    }
    logging::init(&data_dir());
    if show_requested {
        log::info!("--show: no running copy to hand the window to; starting");
    }
    update::clean_portable();
    stream::init_dev_hooks();
    proxy::init(&data_dir());

    let background = std::env::var_os("NUVX_BACKGROUND").is_some();
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("nuvx")
        .with_app_id("nuvx")
        .with_inner_size(DEFAULT_SIZE)
        .with_min_inner_size(MIN_SIZE)
        .with_decorations(!ui::custom_titlebar())
        .with_icon(appicon::get(appicon::load(&data_dir())).icon_data().expect("bundled icons are valid PNGs"));
    if background {
        viewport = viewport.with_position([-4000.0, 0.0]).with_inner_size([1920.0, 1057.0]).with_active(false).with_taskbar(false);
    }
    let renderer = renderer::init(&data_dir());
    material::init(&data_dir());
    let viewport = viewport.with_transparent(material::SUPPORTED);
    let options = eframe::NativeOptions {
        viewport,
        renderer: match renderer {
            renderer::Renderer::OpenGl => eframe::Renderer::Glow,
            renderer::Renderer::Wgpu => eframe::Renderer::Wgpu,
        },
        persist_window: !background,
        persistence_path: Some(data_dir().join("app.ron")),
        multisampling: 0,
        depth_buffer: 0,
        stencil_buffer: 0,
        glow_options: eframe::egui_glow::GlowConfiguration { vsync: std::env::var_os("NUVX_FPS").is_none(), ..Default::default() },
        wgpu_options: renderer::wgpu_options(),
        ..Default::default()
    };

    let run = eframe::run_native(
        "nuvx",
        options,
        Box::new(|cc| {
            let ctx = cc.egui_ctx.clone();
            theme::install_style(&ctx);
            theme::install_fonts(&ctx, "inter");

            static CTX: OnceLock<egui::Context> = OnceLock::new();
            let _ = CTX.set(ctx.clone());
            let wake = || {
                if let Some(c) = CTX.get() {
                    c.request_repaint();
                }
            };

            deeplink::listen(wake);
            if update::installed() && !cfg!(debug_assertions) {
                deeplink::register_protocol();
            }

            let volume = cc
                .storage
                .and_then(|s| s.get_string("volume"))
                .and_then(|v| v.parse().ok())
                .unwrap_or(0.8f32);
            let log_setting = cc.storage.and_then(|s| s.get_string("log_volume"));
            let log_volume = log_setting.as_deref().is_none_or(|v| v == "1");
            let volume = if log_setting.is_none() { volume.cbrt() } else { volume };
            let volume = if std::env::var_os("NUVX_MUTE").is_some() { 0.0 } else { volume };

            let device = cc.storage.and_then(|s| s.get_string("output_device")).filter(|d| !d.is_empty());

            let (audio_tx, audio_rx) = unbounded();
            let audio = audio::spawn(volume, log_volume, device, audio_tx, wake);

            let data_dir = data_dir();
            let (resp_tx, resp_rx) = unbounded();
            let backend = backend::Backend::new(data_dir, resp_tx, audio.clone(), Arc::new(wake))?;

            egui_extras::install_image_loaders(&ctx);
            ctx.add_bytes_loader(Arc::new(images::HttpImages::new(backend.clone())));

            let (media_tx, media_rx) = unbounded();
            if let Some(h) = hwnd(cc) {
                appicon::set_hwnd(h);
                fullscreen::set_hwnd(h);
                ui::set_titlebar_hwnd(h);
                material::set_hwnd(h);
                appicon::set_window_icon(appicon::get(appicon::load(backend.data_dir())));
            }
            #[cfg(target_os = "macos")]
            {
                use raw_window_handle::{HasWindowHandle, RawWindowHandle};
                if let Ok(RawWindowHandle::AppKit(h)) = cc.window_handle().map(|h| h.as_raw()) {
                    material::set_view(h.ns_view.as_ptr());
                }
            }
            let media = media::Media::new(hwnd(cc), media_tx, wake);

            if let Some(gl) = &cc.gl {
                use eframe::glow::HasContext;
                let (renderer, version) = unsafe { (gl.get_parameter_string(eframe::glow::RENDERER), gl.get_parameter_string(eframe::glow::VERSION)) };
                log::info!("gl: {renderer} ({version})");
            }
            if let Some(rs) = &cc.wgpu_render_state {
                let info = rs.adapter.get_info();
                log::info!("wgpu: {} ({:?}, driver {} {})", info.name, info.backend, info.driver, info.driver_info);
            }
            let shader = std::env::var_os("NUVX_NO_SHADER").is_none();
            let bg = match (&cc.gl, &cc.wgpu_render_state) {
                (Some(gl), _) if shader => shader::Background::new(gl).map_err(|e| log::warn!("background shader unavailable: {e:#}")).ok(),
                (_, Some(rs)) if shader => Some(shader::Background::new_wgpu(rs)),
                _ => None,
            };

            Ok(Box::new(ui::App::new(
                backend,
                audio,
                ui::Channels { resp_rx, audio_rx, media_rx },
                media,
                bg,
                std::env::args_os().skip(1).any(|a| a == deeplink::NO_TRAY_FLAG),
                cc.storage,
            )))
        }),
    );
    if run.is_err() && renderer == renderer::Renderer::Wgpu {
        log::error!("renderer: {} failed to start; switching back to OpenGL", renderer::api_name());
        let _ = renderer::save(&data_dir(), renderer::Renderer::OpenGl);
    }
    run
}

#[cfg(windows)]
fn hwnd(cc: &eframe::CreationContext) -> Option<*mut std::ffi::c_void> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    match cc.window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(h) => Some(h.hwnd.get() as *mut std::ffi::c_void),
        _ => None,
    }
}

#[cfg(not(windows))]
fn hwnd(_: &eframe::CreationContext) -> Option<*mut std::ffi::c_void> {
    None
}

fn data_dir() -> std::path::PathBuf {
    if let Some(d) = std::env::var_os("NUVX_DATA_DIR") {
        return d.into();
    }
    if let Some(dir) = update::portable_dir() {
        return dir.join("data");
    }
    directories::ProjectDirs::from("", "", "nuvx").expect("no home directory").data_dir().to_path_buf()
}

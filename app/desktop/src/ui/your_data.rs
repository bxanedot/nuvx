//! Settings → Your data: what nuvx keeps on this computer, what leaves it and when, and the controls for it
//! (the desktop side of the master prompt's Privacy & Data Center, like Android's Settings → Your data).

use egui::Ui;

use super::pages::{section_label, setting_row, settings_card};
use super::widgets::pill;
use super::{Action, App, Page, SettingsPane};

pub(super) fn pane(app: &mut App, ui: &mut Ui) {
    section_label(ui, "On this computer", None);
    settings_card(ui, |ui| {
        setting_row(ui, "Stays here", Some("Playlists made in nuvx, downloads, listening history, searches, settings and the keys of paired phones are stored on this computer only. Your YouTube Music sign-in is kept in the system's credential store."), |_| {});
        let mut open = false;
        setting_row(ui, "Data folder", Some("Where nuvx keeps its files. Back it up to move everything to another computer."), |ui| {
            open = pill(ui, "Open", None, false).clicked();
        });
        if open {
            crate::downloads::open_dir(app.backend.data_dir());
        }
        let n = app.local_playlists.len();
        let desc = format!("Your {n} playlist{} as a file the phone's backup can read, and the other way round. Importing adds playlists you don't have yet.", if n == 1 { "" } else { "s" });
        let (mut export, mut import) = (false, false);
        setting_row(ui, "Backup", Some(&desc), |ui| {
            import = pill(ui, "Import…", None, false).clicked();
            export = pill(ui, "Export…", None, false).clicked();
        });
        if export {
            export_backup(app);
        }
        if import {
            import_backup(app);
        }
        let n = app.recent_searches.len();
        let mut clear = false;
        let desc = if n == 0 { "None saved.".to_owned() } else { format!("{n} saved on this computer.") };
        setting_row(ui, "Recent searches", Some(&desc), |ui| {
            ui.add_enabled_ui(n > 0, |ui| clear = pill(ui, "Clear", None, false).clicked());
        });
        if clear {
            app.recent_searches.clear();
            app.toast("Recent searches cleared");
        }
    });
    ui.add_space(18.0);
    section_label(ui, "What leaves it, and when", None);
    settings_card(ui, |ui| {
        for (what, when) in [
            ("YouTube Music", "Searches, playback and your account, when you use them."),
            ("Listen Together", "The shared room, through nuvx's relay, only while you are in one."),
            ("Discord and Last.fm", "What you play, only if you connect them in Account & integrations."),
            ("Sync & Connect", "Playlists and playback, only to phones you paired, on your network, encrypted."),
            ("Updates", "nuvx asks GitHub for the latest release."),
        ] {
            setting_row(ui, what, Some(when), |_| {});
        }
        let paired = app.lan.paired_count();
        let mut manage = false;
        let desc = match paired {
            0 => "No phones paired.".to_owned(),
            1 => "1 phone paired.".to_owned(),
            n => format!("{n} phones paired."),
        };
        setting_row(ui, "Paired devices", Some(&desc), |ui| {
            manage = pill(ui, "Manage", None, false).clicked();
        });
        if manage {
            app.actions.push(Action::Go(Page::Setting(SettingsPane::Sync)));
        }
        let mut copy = false;
        setting_row(ui, "Copy diagnostics", Some("For a bug report: version, system, network mode and this run's latest warnings and errors. No account, song history, addresses or folder names. Copied only when you press it; nothing is sent."), |ui| {
            copy = pill(ui, "Copy", None, false).clicked();
        });
        if copy {
            let facts = [
                ("Version", crate::update::current_version().to_string()),
                ("System", format!("{} {}", std::env::consts::OS, std::env::consts::ARCH)),
                ("Renderer", format!("{:?}", crate::renderer::active())),
                ("Network", app.proxy_saved.label().to_owned()),
                ("Signed in", if app.logged_in { "yes" } else { "no" }.to_owned()),
                // Sync and output state, as counts and yes/no only: no device names, which can identify a person.
                ("Local Wi-Fi Sync", if app.lan.enabled { format!("on, {} paired", app.lan.paired_count()) } else { "off".to_owned() }),
                ("Remote control", if app.lan.connect { "allowed" } else { "off" }.to_owned()),
                ("Output", if app.output_device.is_some() { "chosen device" } else { "system default" }.to_owned()),
                ("Equalizer", if app.eq.on { "on" } else { "off" }.to_owned()),
                ("Download limit", match crate::downloads::limit_gb() { 0 => "off".to_owned(), n => format!("{n} GB") }),
            ];
            ui.ctx().copy_text(crate::logging::diagnostics(app.backend.data_dir(), &facts));
            app.toast("Diagnostics copied");
        }
    });
}

fn export_backup(app: &mut App) {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_secs());
    let stamp = nuvx_core::backup::iso_utc(now);
    let name = format!("nuvx-backup-{}.json", &stamp[..10]);
    let Some(path) = rfd::FileDialog::new().set_title("Export backup").set_file_name(name).add_filter("nuvx backup", &["json"]).save_file() else { return };
    let lists: Vec<_> = app.local_playlists.iter().map(|p| nuvx_core::backup::Playlist { id: p.id.clone(), title: p.title.clone(), tracks: p.tracks.clone() }).collect();
    let text = nuvx_core::backup::export(&lists, &crate::update::current_version().to_string(), &stamp);
    match std::fs::write(&path, text) {
        Ok(()) => app.toast("Backup saved"),
        Err(e) => app.toast(&format!("Couldn't save the backup: {e}")),
    }
}

fn import_backup(app: &mut App) {
    let Some(path) = rfd::FileDialog::new().set_title("Import backup").add_filter("nuvx backup", &["json"]).pick_file() else { return };
    // Read at most one byte over the limit so a huge file is refused without loading it all.
    let mut bytes = Vec::new();
    let read = std::fs::File::open(&path).and_then(|f| std::io::Read::read_to_end(&mut std::io::Read::take(f, nuvx_core::backup::MAX_BYTES as u64 + 1), &mut bytes));
    if let Err(e) = read {
        return app.toast(&format!("Couldn't read that file: {e}"));
    }
    let lists = match nuvx_core::backup::import(&String::from_utf8_lossy(&bytes)) {
        Ok(lists) => lists,
        Err(why) => return app.toast(&why),
    };
    let mut added = 0;
    for list in lists {
        if app.local_playlists.iter().any(|p| p.id == list.id) {
            continue;
        }
        app.local_playlists.push(super::LocalPlaylist { id: list.id.clone(), title: list.title, tracks: list.tracks, artwork: None });
        app.refresh_local_playlist(&list.id);
        added += 1;
    }
    app.toast(&match added {
        0 => "Nothing new in that backup".to_owned(),
        1 => "Added 1 playlist".to_owned(),
        n => format!("Added {n} playlists"),
    });
}

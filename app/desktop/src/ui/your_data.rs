//! Settings → Your data: what nuvx keeps on this computer, what leaves it and when, and the controls for it
//! (the desktop side of the master prompt's Privacy & Data Center, like Android's Settings → Your data).

use egui::Ui;
use std::sync::Arc;

#[derive(Clone)]
struct PendingBackup {
    lists: Vec<nuvx_core::backup::Playlist>,
}

fn backup_preview_id() -> egui::Id {
    egui::Id::unique("pending-backup-import")
}

fn clear_searches_preview_id() -> egui::Id {
    egui::Id::new("confirm-local-recent-searches-clear")
}

/// Only the local recent-search category is mutated; remote and Sync data are untouched.
fn clear_recent_searches(searches: &mut Vec<super::RecentSearch>, confirmed: bool) -> usize {
    if !confirmed { return 0; }
    let removed = searches.len();
    searches.clear();
    removed
}

/// Prepare the complete replacement in memory; do not mutate an existing playlist.
fn planned_restore(
    current: &[super::LocalPlaylist],
    incoming: &[nuvx_core::backup::Playlist],
) -> (Vec<super::LocalPlaylist>, Vec<String>) {
    let mut next = current.to_vec();
    let mut seen: std::collections::HashSet<String> = current.iter().map(|p| p.id.clone()).collect();
    let mut added = Vec::new();
    for p in incoming {
        if !seen.insert(p.id.clone()) {
            continue;
        }
        next.push(super::LocalPlaylist {
            id: p.id.clone(), title: p.title.clone(), tracks: p.tracks.clone(), artwork: None,
        });
        added.push(p.id.clone());
    }
    (next, added)
}


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
            import_backup(app, ui.ctx());
        }
        let n = app.recent_searches.len();
        let mut clear = false;
        let desc = if n == 0 { "None saved.".to_owned() } else { format!("{n} saved on this computer.") };
        setting_row(ui, "Recent searches", Some(&desc), |ui| {
            ui.add_enabled_ui(n > 0, |ui| clear = pill(ui, "Clear", None, false).clicked());
        });
        if clear {
            ui.ctx().data_mut(|d| d.insert_temp(clear_searches_preview_id(), true));
        }
    });
    confirm_recent_searches_clear(app, ui);
    restore_preview(app, ui);
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

fn confirm_recent_searches_clear(app: &mut App, ui: &mut Ui) {
    if !ui.ctx().data(|d| d.get_temp::<bool>(clear_searches_preview_id()).unwrap_or(false)) {
        return;
    }
    let (mut cancel, mut confirm) = (false, false);
    egui::Window::new("Clear recent searches?")
        .collapsible(false)
        .resizable(false)
        .show(ui.ctx(), |ui| {
            ui.label(format!(
                "Delete {} recent searches stored on this computer? This does not delete YouTube Music account history, playlists, or Sync/cloud data.",
                app.recent_searches.len()
            ));
            ui.horizontal(|ui| {
                cancel = ui.button("Cancel").clicked();
                confirm = ui.button("Delete local searches").clicked();
            });
        });
    if cancel || confirm {
        ui.ctx().data_mut(|d| d.remove_temp::<bool>(clear_searches_preview_id()));
    }
    if confirm {
        let removed = clear_recent_searches(&mut app.recent_searches, true);
        app.toast(format!("Deleted {removed} local recent searches"));
    }
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

fn import_backup(app: &mut App, ctx: &egui::Context) {
    let Some(path) = rfd::FileDialog::new().set_title("Import backup").add_filter("nuvx backup", &["json"]).pick_file() else { return };
    // Read at most one byte over the limit so a huge file is refused without loading it all.
    let mut bytes = Vec::new();
    let read = std::fs::File::open(&path).and_then(|f| std::io::Read::read_to_end(&mut std::io::Read::take(f, nuvx_core::backup::MAX_BYTES as u64 + 1), &mut bytes));
    if let Err(e) = read {
        return app.toast(&format!("Couldn't read that file: {e}"));
    }
    let text = match String::from_utf8(bytes) {
        Ok(s) => s,
        Err(_) => return app.toast("That backup is not valid UTF-8"),
    };
    let lists = match nuvx_core::backup::import(&text) {
        Ok(lists) => lists,
        Err(why) => return app.toast(&why),
    };
    ctx.data_mut(|d| d.insert_temp(backup_preview_id(), Arc::new(PendingBackup { lists })));
}

fn restore_preview(app: &mut App, ui: &mut Ui) {
    let Some(preview) = ui.ctx().data(|d| d.get_temp::<Arc<PendingBackup>>(backup_preview_id())) else { return };
    let mut seen: std::collections::HashSet<&str> = app.local_playlists.iter().map(|p| p.id.as_str()).collect();
    let additions: Vec<_> = preview.lists.iter().filter(|p| seen.insert(p.id.as_str())).collect();
    let skipped = preview.lists.len() - additions.len();
    let mut cancel = false;
    let mut apply = false;
    egui::Window::new("Restore playlists from backup")
        .collapsible(false)
        .resizable(false)
        .show(ui.ctx(), |ui| {
            ui.set_min_width(340.0);
            ui.label(format!("{} new playlists, {} already present or repeated. Existing playlists will not be replaced.", additions.len(), skipped));
            for list in additions.iter().take(5) {
                ui.label(format!("• {} ({} songs)", list.title, list.tracks.len()));
            }
            if additions.len() > 5 { ui.label(format!("…and {} more", additions.len() - 5)); }
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                cancel = ui.button("Cancel").clicked();
                apply = ui.add_enabled(!additions.is_empty(), egui::Button::new("Restore playlists")).clicked();
            });
        });
    if cancel || apply {
        ui.ctx().data_mut(|d| { d.remove_temp::<Arc<PendingBackup>>(backup_preview_id()); });
    }
    if apply {
        // All parsing and deduplication was completed before any state change. Commit together.
        let (updated, ids) = planned_restore(&app.local_playlists, &preview.lists);
        app.local_playlists = updated;
        for id in &ids { app.refresh_local_playlist(id); }
        app.toast(format!("Restored {} playlists", ids.len()));
    }
}

#[cfg(test)]
mod backup_restore_tests {
    use super::*;

    #[test]
    fn recent_searches_are_untouched_until_explicit_confirmation() {
        let mut searches = vec![super::super::RecentSearch {
            query: "private query".into(),
            target: None,
            title: "private query".into(),
            subtitle: String::new(),
            thumb: None,
            round: false,
        }];
        assert_eq!(clear_recent_searches(&mut searches, false), 0);
        assert_eq!(searches.len(), 1, "cancel must preserve the local category");
        assert_eq!(clear_recent_searches(&mut searches, true), 1);
        assert!(searches.is_empty());
        assert_eq!(clear_recent_searches(&mut searches, true), 0);
    }

    #[test]
    fn valid_backups_are_previewed_and_corrupt_backups_leave_data_intact() {
        let existing = vec![super::super::LocalPlaylist {
            id: "already".into(), title: "Kept".into(), tracks: vec![], artwork: None,
        }];
        let valid = r#"{"app":"nuvx","version":1,"playlists":[{"id":"already","title":"Replace?"},{"id":"new","title":"Fresh"},{"id":"new","title":"Again"}]}"#;
        let parsed = nuvx_core::backup::import(valid).unwrap();
        let (restored, ids) = planned_restore(&existing, &parsed);
        assert_eq!(ids, ["new"]);
        assert_eq!(restored.len(), 2);
        assert_eq!(restored[0].title, "Kept");
        assert_eq!(restored[1].title, "Fresh");
        assert!(nuvx_core::backup::import("{broken").is_err());
        assert_eq!(existing.len(), 1, "a preview or invalid backup never mutates original data");
    }
}


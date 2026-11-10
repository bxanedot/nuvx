use std::collections::{HashMap, HashSet};

use egui::{Rect, Sense, Ui, pos2, vec2};

use super::widgets::{self, chip, named};
use super::{Action, App, glyphs};
use crate::backend::Req;
use crate::radio::{Country, Station};
use crate::theme;

#[derive(Default)]
pub struct RadioState {
    countries: Option<Result<Vec<Country>, String>>,
    stations: HashMap<String, Result<Vec<Station>, String>>,
    country: Option<String>,
    query: String,
    search: String,
    asked: HashSet<String>,
}

impl RadioState {
    pub fn set_countries(&mut self, list: Result<Vec<Country>, String>) {
        let list = list.map(|mut l| {
            let home = crate::radio::home_country();
            l.sort_by_key(|c| home.as_deref().is_none_or(|h| !c.code.eq_ignore_ascii_case(h)));
            l
        });
        if self.country.is_none() {
            self.country = list.as_ref().ok().and_then(|l| l.first()).map(|c| c.code.clone());
        }
        self.countries = Some(list);
    }

    pub fn set_stations(&mut self, key: String, stations: Result<Vec<Station>, String>) {
        self.stations.insert(key, stations);
    }

    fn key(&self) -> Option<String> {
        if self.search.is_empty() { self.country.clone() } else { Some(format!("search:{}", self.search)) }
    }

    fn retry(&mut self) {
        self.countries.take_if(|c| c.is_err());
        self.stations.retain(|_, s| s.is_ok());
        self.asked.clear();
    }
}

pub(super) fn page(app: &mut App, ui: &mut Ui, now: f64) {
    let current = app.current_id().map(str::to_owned);
    let playing = app.playing();
    let on_air = playing && current.as_deref().is_some_and(crate::radio::is_station);
    heading(ui, on_air);

    let r = &mut app.radio;
    if r.countries.is_none() && r.asked.insert("countries".into()) {
        app.backend.request(Req::RadioCountries);
    }
    if let Some(key) = r.key()
        && !r.stations.contains_key(&key)
        && r.asked.insert(key.clone())
    {
        let (country, query) = if r.search.is_empty() { (r.country.clone(), None) } else { (None, Some(r.search.clone())) };
        app.backend.request(Req::RadioStations { key, country, query });
    }

    ui.add_space(6.0);
    ui.scope(|ui| {
        ui.set_max_width(ui.available_width().min(560.0));
        let resp = super::dialogs::text_field(ui, "", &mut r.query, false, "Search stations");
        if resp.changed() && r.query.trim().is_empty() {
            r.search.clear();
        }
        if resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) {
            r.search = r.query.trim().to_owned();
        }
    });

    if r.search.is_empty() {
        ui.add_space(14.0);
        match &r.countries {
            Some(Ok(list)) => {
                let mut pick = None;
                egui::ScrollArea::horizontal().id_salt("radio-countries").scroll_bar_visibility(egui::scroll_area::ScrollBarVisibility::AlwaysHidden).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = 8.0;
                        for c in list {
                            let label = format!("{} {}", c.flag(), c.name).trim().to_owned();
                            if chip(ui, &label, r.country.as_deref() == Some(c.code.as_str())).clicked() {
                                pick = Some(c.code.clone());
                            }
                        }
                    });
                });
                if pick.is_some() {
                    r.country = pick;
                }
            }
            Some(Err(_)) => {}
            None => loading(ui, now, 1, 34.0),
        }
    }

    ui.add_space(12.0);
    let stations = r.key().and_then(|k| r.stations.get(&k));
    let no_countries = r.search.is_empty() && matches!(r.countries, Some(Err(_)));
    match stations {
        Some(Err(_)) => retry(ui, r),
        _ if no_countries => retry(ui, r),
        None => loading(ui, now, 6, 64.0),
        Some(Ok(list)) if list.is_empty() => {
            ui.add_space(24.0);
            ui.label(egui::RichText::new("No stations found").color(theme::text_dim()));
        }
        Some(Ok(list)) => {
            let mut tune = None;
            for s in list {
                let track_id = format!("{}{}", crate::radio::PREFIX, s.stream);
                if station_row(ui, s, current.as_deref() == Some(track_id.as_str()), playing).clicked() {
                    tune = Some(s.clone());
                }
            }
            if let Some(s) = tune {
                app.backend.request(Req::RadioClick(s.id.clone()));
                app.actions.push(Action::Play(vec![s.track()], 0));
            }
        }
    }
}

fn heading(ui: &mut Ui, on_air: bool) {
    ui.add_space(18.0);
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 42.0), Sense::hover());
    let g = ui.painter().layout_no_wrap("Radio".into(), theme::bold(34.0), theme::text());
    let w = g.size().x;
    ui.painter().galley(pos2(rect.left(), rect.center().y - g.size().y / 2.0), g, theme::text());
    glyphs::draw_pulsing(ui, pos2(rect.left() + w + 12.0 + 15.0, rect.center().y), 30.0, glyphs::RADIO, accent(ui), resp.id, on_air);
}

fn retry(ui: &mut Ui, r: &mut RadioState) {
    ui.add_space(24.0);
    ui.vertical_centered(|ui| {
        ui.label(egui::RichText::new("Couldn't load radio stations").color(theme::text_dim()));
        ui.add_space(10.0);
        if widgets::pill(ui, "Retry", None, false).clicked() {
            r.retry();
        }
    });
}

fn accent(ui: &Ui) -> egui::Color32 {
    ui.ctx().data(|d| d.get_temp::<egui::Color32>(egui::Id::unique("accent"))).unwrap_or(theme::RED)
}

fn loading(ui: &mut Ui, now: f64, rows: usize, height: f32) {
    for _ in 0..rows {
        let (rect, _) = ui.allocate_exact_size(vec2(ui.available_width(), height), Sense::hover());
        widgets::shimmer_band(ui, rect.shrink2(vec2(0.0, 4.0)), 10.0, now);
    }
    ui.ctx().request_repaint_after(std::time::Duration::from_millis(66));
}

fn station_row(ui: &mut Ui, s: &Station, on_air: bool, playing: bool) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(ui.available_width(), 64.0), Sense::click());
    let resp = named(resp, s.name.trim()).on_hover_cursor(egui::CursorIcon::PointingHand);
    if !ui.is_rect_visible(rect) {
        return resp;
    }
    let h = theme::anim_bool(ui.ctx(), resp.id.with("h"), resp.hovered(), 0.12);
    ui.painter().rect_filled(rect, 10.0, theme::glass(0.06 * h));
    let art = Rect::from_min_size(pos2(rect.left() + 8.0, rect.center().y - 24.0), vec2(48.0, 48.0));
    ui.painter().rect_filled(art, 10.0, theme::glass(0.07));
    glyphs::draw(ui, art.center(), 22.0, glyphs::RADIO, theme::text_dim());
    if s.favicon.starts_with("http") {
        widgets::paint_remote(ui, &s.favicon, art, 10.0, Rect::from_min_max(pos2(0.0, 0.0), pos2(1.0, 1.0)), egui::Color32::WHITE);
    }
    let x = art.right() + 14.0;
    let right = rect.right() - if on_air { 44.0 } else { 12.0 };
    let name_color = if on_air { accent(ui) } else { theme::text() };
    let name = widgets::galley_wrapped(ui, s.name.trim(), theme::semibold(15.0), name_color, right - x, 1);
    let sub = s.subtitle();
    let top = if sub.is_empty() { rect.center().y - name.size().y / 2.0 } else { rect.center().y - name.size().y };
    ui.painter().galley(pos2(x, top), name, name_color);
    if !sub.is_empty() {
        let g = widgets::galley_wrapped(ui, &sub, theme::regular(13.0), theme::text_dim(), right - x, 1);
        ui.painter().galley(pos2(x, rect.center().y + 2.0), g, theme::text_dim());
    }
    if on_air {
        glyphs::draw_pulsing(ui, pos2(rect.right() - 24.0, rect.center().y), 20.0, glyphs::WAVEFORM, accent(ui), resp.id.with("wave"), playing);
    }
    resp
}

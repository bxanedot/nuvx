use super::*;

pub(super) fn volume_popover(app: &mut App, ui: &mut Ui, anchor: Rect) {
    let ctx = ui.ctx().clone();
    let id = egui::Id::unique("bar-vol-pop");
    let panel = Rect::from_min_size(pos2(anchor.center().x - 80.0, anchor.bottom() + 6.0), vec2(160.0, 34.0));
    let over = |r: Rect| ctx.input(|i| i.pointer.hover_pos()).is_some_and(|p| r.contains(p));
    let was = ctx.data(|d| d.get_temp::<bool>(id).unwrap_or(false));
    let bridge = Rect::from_min_max(pos2(anchor.left(), anchor.center().y), pos2(anchor.right(), panel.top() + 2.0));
    let open = over(anchor.expand(2.0)) || (was && (over(panel) || over(bridge) || ctx.input(|i| i.pointer.any_down())));
    ctx.data_mut(|d| d.insert_temp(id, open));
    let t = theme::anim_bool(&ctx, id.with("t"), open, 0.12);
    if t <= 0.0 {
        return;
    }
    egui::Area::new(id.with("area")).order(egui::Order::Foreground).fixed_pos(panel.min).interactable(true).show(&ctx, |ui| {
        let (rect, _) = ui.allocate_exact_size(panel.size(), Sense::hover());
        ui.painter().rect_filled(rect, 10.0, theme::panel(0.96).gamma_multiply(t));
        ui.painter().rect_stroke(rect, 10.0, egui::Stroke::new(1.0, theme::glass(0.12 * t)), egui::StrokeKind::Inside);
        volume_slider(app, ui, rect.shrink2(vec2(16.0, 7.0)), "bar");
    });
}

fn volume_slider(app: &mut App, ui: &mut Ui, slider: Rect, salt: &str) {
    let vol = app.audio.volume();
    let vresp = ui.interact(slider, ui.scope_id().with(salt), Sense::click_and_drag()).on_hover_cursor(egui::CursorIcon::PointingHand);
    vresp.widget_info(|| egui::WidgetInfo::slider(true, vol as f64, "Volume"));
    let vh = theme::anim_bool(ui.ctx(), ui.scope_id().with((salt, "h")), vresp.hovered() || vresp.dragged(), 0.15);
    let track_r = Rect::from_center_size(slider.center(), vec2(slider.width(), 4.0));
    ui.painter().rect_filled(track_r, 2.0, theme::glass(0.15));
    let fill = Rect::from_min_size(track_r.min, vec2(track_r.width() * vol, 4.0));
    ui.painter().rect_filled(fill, 2.0, theme::lerp_color(theme::text_dim(), theme::text(), vh));
    ui.painter().circle_filled(pos2(fill.right(), track_r.center().y), 2.0 + 4.5 * vh, theme::text());
    if let Some(p) = vresp.interact_pointer_pos()
        && (vresp.dragged() || vresp.clicked())
    {
        app.audio.set_volume(((p.x - track_r.left()) / track_r.width()).clamp(0.0, 1.0));
    }
    if vresp.hovered() {
        let scroll = ui.input(|i| i.smooth_scroll_delta.y);
        if scroll != 0.0 {
            app.audio.set_volume((vol + scroll / 600.0).clamp(0.0, 1.0));
        }
    }
}

use super::*;

impl View {
    pub fn reset(&mut self) {
        *self = Self { glow: std::mem::take(&mut self.glow), ..Default::default() };
    }

    pub fn release_hidden(&mut self, pass: u64) {
        if self.shown_pass != 0 && self.shown_pass + 1 < pass {
            self.glow = Glow::default();
            self.shown_pass = 0;
        }
    }

    pub fn resume(&mut self) {
        self.user_until = 0.0;
    }

    pub fn text_glow(&mut self, ctx: &egui::Context, g: Arc<Galley>, at: Pos2, blur: f32) -> Option<(TextureId, Rect, Rect)> {
        let atlas = ctx.fonts(|fv| fv.font_image_size());
        let ppp = ctx.pixels_per_point();
        let key = (hash(("text-glow", at.x.round() as i32, at.y.round() as i32, g.job.text.as_str())), usize::MAX);
        self.glow.blurred_line(ctx, key, blur / 2.0, &[], &[(g, at, 1.0)], &[], 1.0, atlas, ppp)
    }

    pub fn show(&mut self, ui: &mut egui::Ui, f: Frame) -> Out {
        let rect = f.rect;
        let ctx = ui.ctx().clone();
        self.shown_pass = ctx.cumulative_pass_nr();
        let cfg = Cfg::new(f.style, f.accent, rect.width(), ctx.content_rect().width(), f.centered, f.scale);
        let layout_key = hash((f.key, rect.width().round() as i32, cfg.size.to_bits(), cfg.padding.to_bits(), cfg.max_scale().to_bits(), cfg.centered, &cfg.font, cfg.weight as u8));
        if self.layout.as_ref().is_none_or(|l| l.key != layout_key) {
            self.relayout(ui, &cfg, &f, layout_key, rect);
        }
        let layout = self.layout.as_ref().unwrap();
        let richsync = layout.richsync;
        let lines_n = layout.blocks.iter().filter(|b| b.line.is_some()).count();

        let t = f.t + if richsync { RICHSYNC_OFFSET } else { LINE_OFFSET };
        let jumped = (t - self.last_t - (f.now - self.last_wall)).abs() > TIME_JUMP || self.last_wall == 0.0;
        self.last_t = t;
        self.last_wall = f.now;

        let early = if f.playing { 2.0 } else { 0.0 };
        let mut animating = false;
        for i in 0..lines_n {
            let b = &layout.blocks[i];
            let next = layout.blocks.get(i + 1).filter(|n| n.line.is_some()).map_or(f64::INFINITY, |n| n.start);
            let end = next.max(b.start + b.dur + 0.05);
            let st = &mut self.states[i];
            let selected = t + early >= b.start && t < end;
            if selected && !st.selected {
                st.selected = true;
                st.exit = None;
            } else if !selected && st.selected {
                st.selected = false;
                let stale = end + FADE_OUT + 0.05;
                st.exit = if (f.playing || jumped) && t <= stale { Some(f.now) } else { None };
                st.exit_t = t;
            } else if !selected && jumped {
                st.exit = None;
            }
            if st.exit.is_some_and(|e| f.now - e > FADE_OUT.max(SCALE_DURATION)) {
                st.exit = None;
            }
            animating |= st.exit.is_some();
        }

        let view_h = rect.height();
        let scroll_time = t + SCROLL_OFFSET;
        let user = f.now < self.user_until;
        let mut active: Vec<usize> = Vec::new();
        let mut new_lyric = jumped || (self.was_user && !user);
        self.was_user = user;
        for i in 0..lines_n {
            let b = &layout.blocks[i];
            let next = layout.blocks.get(i + 1).filter(|n| n.line.is_some()).map_or(f64::INFINITY, |n| n.start);
            if scroll_time >= b.start - EARLY_SCROLL && (scroll_time < next || scroll_time < b.start + b.dur) {
                active.push(i);
                if !self.last_scroll.contains(&i) && scroll_time >= b.start {
                    new_lyric = true;
                }
            }
        }
        let active_real = !active.is_empty();
        let last_sung = (0..lines_n).rev().find(|&i| !layout.blocks[i].instrumental);
        let credits = layout.blocks.iter().position(|b| b.credits.is_some());
        let credits_focused = credits.is_some() && last_sung.is_some_and(|i| scroll_time >= layout.blocks[i].start + layout.blocks[i].dur);
        if credits_focused != self.credits_focused {
            self.credits_focused = credits_focused;
            new_lyric = true;
        }
        let line_h = cfg.size * 1.333;
        let sung_y = |i: usize| -> Option<f32> {
            let b = &layout.blocks[i];
            if b.height <= view_h || scroll_time < b.start {
                return None;
            }
            let mut timed = b.words.iter().filter(|w| !w.line_synced && !w.bg).peekable();
            Some(if timed.peek().is_none() {
                b.height * ((scroll_time - b.start) / b.dur.max(0.001)).clamp(0.0, 1.0) as f32
            } else {
                timed.filter(|w| w.start <= scroll_time).map(|w| w.pos.y).fold(0.0, f32::max)
            })
        };
        let page = active.last().and_then(|&i| sung_y(i).map(|y| (i, (y / line_h) as i32)));
        if page != self.page {
            self.page = page;
            new_lyric |= page.is_some();
        }
        let pos = |i: usize| layout.pad_top + layout.blocks[i].top;
        let max_scroll = (layout.content_h - view_h).max(0.0);
        if !user && (new_lyric || !self.scroll_set) && lines_n > 0 {
            if active.is_empty() {
                active.push(0);
            }
            let offset = view_h * cfg.target_ratio;
            let last = *active.last().unwrap();
            let sung: Vec<usize> = active.iter().copied().enumerate().filter(|&(k, i)| scroll_time < layout.blocks[i].start + layout.blocks[i].dur - ENDING_THRESHOLD || k == active.len() - 1).map(|(_, i)| i).collect();
            let anchor = sung
                .iter()
                .rev()
                .find(|&&i| layout.blocks[i].start <= scroll_time && scroll_time < layout.blocks[i].start + layout.blocks[i].dur)
                .or_else(|| sung.iter().rev().find(|&&i| layout.blocks[i].start <= scroll_time))
                .copied()
                .unwrap_or(sung[0]);
            let (first, lastl) = (sung[0], *sung.last().unwrap());
            let centre = sung.iter().map(|&i| pos(i) + layout.blocks[i].height / 2.0).sum::<f32>() / sung.len() as f32;
            let h = |i: usize| layout.blocks[i].height;
            let mut top = centre - offset;
            top = top.max(pos(lastl) + h(lastl) - view_h);
            top = top.min(pos(lastl)).min(pos(first));
            top = top.max(pos(anchor) + h(anchor) - view_h);
            top = top.min(pos(anchor));
            if let Some(y) = sung_y(anchor) {
                top = top.max(pos(anchor) + y + line_h * 2.0 - view_h);
            }
            if credits_focused && let (Some(c), Some(ls)) = (credits, last_sung) {
                top = (pos(c) + h(c) / 2.0 - offset).min(pos(ls));
            }
            let top = top.clamp(0.0, max_scroll);
            if !self.scroll_set {
                self.scroll_top = top;
                self.scroll_set = true;
            } else if (top - self.scroll_top).abs() > 2.0 {
                self.scrolls.push(ScrollAnim { start: f.now, delta: top - self.scroll_top, active: last });
                self.scroll_top = top;
            }
            self.last_scroll = active.clone();
        }

        let hovered = ui.rect_contains_pointer(rect);
        let wheel = if hovered { ui.input(|i| i.smooth_scroll_delta.y) } else { 0.0 };
        if wheel != 0.0 {
            self.scroll_top = (self.scroll_top - wheel).clamp(0.0, max_scroll);
            self.user_until = f.now + USER_SCROLL_RESUME;
            self.scrolls.clear();
        }
        let user = f.now < self.user_until;

        self.scrolls.retain(|a| f.now - a.start < 2.0);
        animating |= !self.scrolls.is_empty();
        for s in &mut self.shifts {
            s.retain(|a| f.now - a.start < DECO_ENTRY);
            animating |= !s.is_empty();
        }

        let painter = ui.painter_at(rect);
        let first_shape = ctx.graphics(|g| g.get(painter.layer_id()).map_or(ShapeIdx(0), |l| l.next_idx()));
        let origin_y = rect.top() - self.scroll_top + layout.pad_top;
        let x0 = rect.left() + cfg.pad.0;
        let line_w = rect.width() - cfg.pad.0 - cfg.pad.1;
        let atlas = ctx.fonts(|fv| fv.font_image_size());
        let ppp = ctx.pixels_per_point();
        let mut seek = None;
        let active_line = active.last().copied();
        let first_active = active.first().copied().filter(|_| active_real);
        let mut glow_work: Vec<GlowJob> = Vec::new();
        let pointer = ui.input(|i| i.pointer.hover_pos()).filter(|p| rect.contains(*p));

        for (i, b) in layout.blocks.iter().enumerate() {
            let mut dy = 0.0;
            for a in &self.scrolls {
                let rel = i as isize - a.active as isize;
                let d = scroll_duration(rel, a.delta);
                let p = ((f.now - a.start) / d).clamp(0.0, 1.0) as f32;
                dy += a.delta * (1.0 - SCROLL_EASE.at(p));
            }
            if let Some(shifts) = self.shifts.get(i) {
                for s in shifts {
                    let p = ((f.now - s.start) / DECO_ENTRY).clamp(0.0, 1.0) as f32;
                    dy += s.delta * (1.0 - DECO_EASE.at(p));
                }
            }
            let top = origin_y + b.top + dy;
            if top > rect.bottom() + 40.0 || top + b.height < rect.top() - 40.0 {
                continue;
            }
            let block_rect = Rect::from_min_size(pos2(x0, top), vec2(line_w, b.height));
            let content = pos2(x0 + cfg.size * 0.25, top);

            if let Some((text, font)) = &b.credits {
                let a = if credits_focused { 0.85 } else { 0.2 };
                let a = theme::anim(&ctx, ("lyr-credits", f.key), a, 0.35);
                let o = transform_origin(block_rect, b.align, content.x);
                let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, line_w - cfg.size * 0.5));
                let xf = Xf { origin: o, scale: cfg.scale, content };
                paint_galley(&painter, &g, pos2(0.0, cfg.padding), &xf, theme::with_alpha(Color32::WHITE, a), atlas);
                continue;
            }
            let li = b.line.unwrap();
            let hovered = pointer.is_some_and(|p| block_rect.contains(p));
            let st = self.states[i];
            let sung_now = st.selected && t >= b.start;

            let (dim, opacity, blur) = if let Some(css) = &cfg.css {
                let on = active.contains(&i) && active_real || st.selected;
                let target = if hovered || on {
                    (1.0, 0.0)
                } else {
                    match first_active {
                        Some(a) if i < a && !user => css.past.map_or((1.0, 0.0), |p| (p[0], p[1])),
                        Some(a) if i > a => css.upcoming.map_or((1.0, 0.0), |p| (p[0], p[1])),
                        _ => (1.0, 0.0),
                    }
                };
                let line_ms = b.dur;
                let (op_t, blur_t) = if hovered {
                    ((0.3, 0.0), (0.3, 0.0))
                } else {
                    let o = css.opacity_transition.unwrap_or([0.0, 0.0]);
                    let fl = css.filter_transition.unwrap_or([0.0, 0.0]);
                    ((line_ms * o[0] as f64, o[1] as f64), (line_ms * fl[0] as f64, fl[1] as f64))
                };
                let s = &mut self.states[i];
                s.opacity.set(target.0, f.now, op_t.0, op_t.1);
                s.blur.set(target.1, f.now, blur_t.0, blur_t.1);
                animating |= s.opacity.moving(f.now) || s.blur.moving(f.now);
                (cfg.inactive_alpha, s.opacity.value(f.now), s.blur.value(f.now))
            } else {
                let rel = active_line.map_or(1, |a| i as isize - a as isize);
                let past = rel < 0;
                let dist = rel.unsigned_abs() as f32;
                let upcoming = (cfg.upcoming_alpha - dist.min(6.0) * cfg.falloff).max(cfg.min_alpha.min(cfg.upcoming_alpha));
                let reveal = theme::anim_bool(&ctx, "lyr-reveal", user, 0.3);
                let dim = if sung_now || rel == 0 {
                    cfg.inactive_alpha
                } else if past {
                    lerp(cfg.past_alpha.unwrap_or(upcoming), upcoming, reveal)
                } else {
                    upcoming
                };
                let dim = theme::anim(&ctx, ("lyr-dim", f.key, i), dim, 0.3);
                let blur_on = match cfg.blur_scope {
                    BlurScope::All => true,
                    BlurScope::Past => past,
                    BlurScope::Upcoming => !past || reveal > 0.0,
                };
                let target = if cfg.blur > 0.0 && blur_on && !sung_now && rel != 0 { (cfg.blur * (1.0 + 0.35 * (dist - 1.0).clamp(0.0, 3.0))).min(9.0) } else { 0.0 };
                let s = &mut self.states[i];
                s.blur.set(target, f.now, 0.35, 0.0);
                animating |= s.blur.moving(f.now);
                (dim, 1.0, s.blur.value(f.now))
            };

            let mut scale = cfg.scale;
            if st.selected && t >= b.start {
                scale = lerp(cfg.scale, cfg.active_scale, EASE.at(((t - b.start) / SCALE_DURATION) as f32));
            } else if let Some(e) = st.exit {
                scale = lerp(cfg.active_scale, cfg.scale, EASE.at(((f.now - e) / SCALE_DURATION) as f32));
            } else if let Some(h) = cfg.css.as_ref().and_then(|c| c.hover_scale) {
                let k = theme::anim_bool(&ctx, ("lyr-hover", f.key, i), hovered, 0.3);
                scale = lerp(cfg.scale, h, EASE.at(k));
            }
            if st.selected && t >= b.start && t < b.start + SCALE_DURATION {
                animating = true;
            }
            let origin = transform_origin(block_rect, b.align, content.x);
            let xf = Xf { origin, scale, content };

            let text_lit = if let Some(e) = st.exit {
                1.0 - EASE.at(((f.now - e) / FADE_OUT) as f32)
            } else if sung_now {
                EASE.at(((t - b.start) / FADE_IN) as f32)
            } else {
                0.0
            };
            let text_alpha = 0.6 * lerp(dim, 1.0, text_lit);
            if b.instrumental {
                animating |= paint_instrumental(&painter, &cfg, b, &xf, t, line_w - cfg.size * 0.5);
            } else if blur > 0.05 {
                let r = (blur * 4.0).round() / 4.0;
                let deco_words: Vec<(&Word, f32)> = b
                    .decos
                    .iter()
                    .flat_map(|d| match &d.body {
                        DecoBody::Timed { words, .. } => words.iter().map(|w| (w, dim)).collect::<Vec<_>>(),
                        DecoBody::Text(..) => vec![],
                    })
                    .collect();
                let texts: Vec<(Arc<Galley>, Pos2, f32)> = b
                    .decos
                    .iter()
                    .filter_map(|d| match &d.body {
                        DecoBody::Text(text, font) => {
                            let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, d.rect.width() + 1.0));
                            let pad = if d.pill { vec2(cfg.rem() * 0.75 + 1.0, cfg.rem() * 0.25 + 1.0) } else { Vec2::ZERO };
                            Some((g, d.rect.min + pad, text_alpha))
                        }
                        DecoBody::Timed { .. } => None,
                    })
                    .collect();
                let words: Vec<(&Word, f32)> = b.words.iter().map(|w| (w, if w.bg { dim * 0.78 } else { dim })).chain(deco_words).collect();
                let pills: Vec<(Rect, f32)> = b.decos.iter().filter(|d| d.pill).map(|d| (d.rect, cfg.rem())).collect();
                if let Some((tex, rr, uv)) = self.glow.blurred_line(&ctx, (f.key, i), r, &words, &texts, &pills, scale, atlas, ppp) {
                    let to = xf.rect(rr);
                    let snapped = (to.min.to_vec2() * ppp).round() / ppp;
                    painter.image(tex, to.translate(snapped - to.min.to_vec2()), uv, theme::with_alpha(Color32::WHITE, opacity));
                }
                animating |= st.exit.is_some();
            } else {
                let lit = LitState::new(&cfg, st, t, f.now, sung_now || st.selected);
                paint_words(&painter, &ctx, &cfg, &b.words, &b.groups, &xf, &lit, dim, opacity, atlas, &mut glow_work, (f.key, i, 0));
                for (k, d) in b.decos.iter().enumerate() {
                    let dxf = xf;
                    let a = opacity;
                    if d.pill {
                        let r = dxf.rect(d.rect);
                        let radius = (cfg.rem() * scale).min(255.0) as u8;
                        painter.rect(r, radius, theme::with_alpha(Color32::WHITE, 0.05 * a), egui::Stroke::new(1.0, theme::with_alpha(Color32::WHITE, 0.1 * a)), egui::StrokeKind::Inside);
                    }
                    match &d.body {
                        DecoBody::Timed { words, groups } => {
                            paint_words(&painter, &ctx, &cfg, words, groups, &dxf, &lit, dim, a, atlas, &mut glow_work, (f.key, i, k as u32 + 1));
                        }
                        DecoBody::Text(text, font) => {
                            let wrap = d.rect.width() + 1.0;
                            let g = ctx.fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, wrap));
                            let pad = if d.pill { vec2(cfg.rem() * 0.75 + 1.0, cfg.rem() * 0.25 + 1.0) } else { Vec2::ZERO };
                            paint_galley(&painter, &g, d.rect.min + pad, &dxf, theme::with_alpha(Color32::WHITE, text_alpha * a), atlas);
                        }
                    }
                }
                animating |= lit.animating(b, t);
            }

            let hit = block_rect.intersect(rect);
            if hit.is_positive() {
                let resp = ui.interact(hit, ui.scope_id().with(("lyr-line", f.key, i)), egui::Sense::click());
                if resp.clicked() && f.lyrics.sync != Sync::None {
                    seek = Some(f.lyrics.lines[li].start as f64 / 1000.0);
                }
                if resp.hovered() {
                    ui.ctx().set_cursor_icon(egui::CursorIcon::PointingHand);
                }
            }
        }
        self.glow.finish(&ctx, &painter, glow_work, atlas, ppp);
        fade_edges(&ctx, painter.layer_id(), first_shape, rect, cfg.size);
        if f.playing {
            animating = true;
        }
        Out { seek, animating, user_scrolling: user }
    }

    fn relayout(&mut self, ui: &mut egui::Ui, cfg: &Cfg, f: &Frame, key: u64, rect: Rect) {
        let width = rect.width() - cfg.pad.0 - cfg.pad.1;
        let lyrics = f.lyrics;
        let mut blocks: Vec<Block> = ui.ctx().fonts_mut(|fonts| {
            lyrics
                .lines
                .iter()
                .enumerate()
                .map(|(i, l)| {
                    let agent = if l.instrumental { nearest_agent(lyrics, i) } else { l.agent.clone() };
                    let roman = f.roman.get(i).and_then(|r| r.as_ref()).map(|r| match r {
                        Roman::Timed(p, t) => Roman::Timed(p, t),
                        Roman::Text(t) => Roman::Text(t),
                    });
                    let trans = f.trans.get(i).copied().flatten();
                    build_block(fonts, cfg, l, i, width, roman, trans, agent.as_deref())
                })
                .collect()
        });
        if !lyrics.songwriters.is_empty() {
            let names = &lyrics.songwriters;
            let text = if names.len() > 1 { format!("Written by {} & {}", names[..names.len() - 1].join(", "), names[names.len() - 1]) } else { format!("Written by {}", names[0]) };
            let font = theme::regular((cfg.size * 0.4).max(12.0));
            let h = ui.ctx().fonts_mut(|fv| fv.layout(text.clone(), font.clone(), Color32::WHITE, width - cfg.size * 0.5).size().y);
            blocks.push(Block {
                line: None,
                start: 0.0,
                dur: 0.0,
                instrumental: false,
                align: if cfg.centered { Align::Center } else { Align::Left },
                top: 0.0,
                height: h + cfg.padding * 2.0,
                words: vec![],
                groups: vec![],
                decos: vec![],
                credits: Some((text, font)),
            });
        }
        let mut y = 0.0;
        for b in &mut blocks {
            b.top = y;
            y += b.height;
        }
        let view_h = rect.height();
        let first_h = blocks.first().map_or(0.0, |b| b.height);
        let pad_top = (view_h * cfg.target_ratio - first_h / 2.0).max(0.0);
        let last_centre = blocks.iter().rev().find(|b| b.line.is_some()).map(|b| b.top + b.height / 2.0);
        let last_h = blocks.iter().rev().find(|b| b.line.is_some()).map_or(0.0, |b| b.height);
        let content = y;
        let trailing = blocks.last().filter(|b| b.credits.is_some()).map_or(0.0, |b| b.height) + last_h / 2.0;
        let want = last_centre.map_or(view_h, |c| c + view_h * (1.0 - cfg.target_ratio));
        let pad_bottom = (want - content).max(view_h - content).max(view_h * (1.0 - cfg.target_ratio) - trailing).max(0.0);
        let richsync = lyrics.sync == Sync::Word;
        let same_song = self.layout.as_ref().is_some_and(|l| l.blocks.len() == blocks.len() && self.states.len() == blocks.len() && l.richsync == richsync && same_lines(l, &blocks));
        if same_song {
            let old = self.layout.as_ref().unwrap();
            let now = ui.input(|i| i.time);
            self.shifts.resize_with(blocks.len(), Vec::new);
            for (i, b) in blocks.iter().enumerate() {
                let delta = (old.pad_top + old.blocks[i].top) - (pad_top + b.top);
                if delta.abs() > 0.5 && (old.width - width).abs() < 1.0 {
                    self.shifts[i].push(Shift { start: now, delta });
                }
            }
        } else {
            let glow = std::mem::take(&mut self.glow);
            *self = Self { glow, ..Default::default() };
            self.states = vec![LineState::default(); blocks.len()];
            self.shifts = vec![Vec::new(); blocks.len()];
        }
        self.layout = Some(Layout { key, width, blocks, pad_top, content_h: pad_top + content + pad_bottom, richsync });
    }
}

fn same_lines(l: &Layout, blocks: &[Block]) -> bool {
    l.blocks.iter().zip(blocks).all(|(a, b)| a.line == b.line && a.start == b.start && a.words.len() == b.words.len())
}

fn nearest_agent(l: &Lyrics, i: usize) -> Option<String> {
    l.lines[i + 1..].iter().chain(l.lines[..i].iter().rev()).find(|x| !x.instrumental && x.agent.is_some()).and_then(|x| x.agent.clone())
}

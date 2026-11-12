use super::*;

#[allow(clippy::too_many_arguments)]
pub(super) fn build_block(fonts: &mut FontsView<'_>, cfg: &Cfg, l: &Line, index: usize, width: f32, roman: Option<Roman>, trans: Option<&str>, agent: Option<&str>) -> Block {
    let em = cfg.size;
    let rtl = crate::bidi::rtl_base(&l.text);
    let align = match agent {
        Some("v2" | "v3") => Align::Right,
        Some("v1000") => Align::Center,
        _ if cfg.centered => Align::Center,
        _ => Align::Left,
    };
    let align = match align {
        Align::Left if rtl => Align::Right,
        Align::Right if rtl => Align::Left,
        a => a,
    };
    let full_w = (width - em * 0.5).max(40.0);
    let content_w = (full_w / cfg.max_scale()).max(40.0);
    let line_h = em * 1.333;
    let mut y = cfg.padding;
    let mut block = Block {
        line: Some(index),
        start: l.start as f64 / 1000.0,
        dur: l.dur.max(0) as f64 / 1000.0,
        instrumental: l.instrumental,
        align,
        top: 0.0,
        height: 0.0,
        words: vec![],
        groups: vec![],
        decos: vec![],
        credits: None,
    };
    if l.instrumental {
        block.height = cfg.padding * 2.0 + line_h;
        return block;
    }
    let parts = if l.parts.is_empty() { line_synced_parts(l) } else { l.parts.clone() };
    let toks: Vec<Tok> = parts.iter().flat_map(split_part).collect();
    let (main, bg): (Vec<Tok>, Vec<Tok>) = toks.into_iter().partition(|t| !t.bg);
    let joined = |toks: &[Tok]| toks.iter().map(|t| t.text.as_str()).collect::<String>();
    let font = cfg.main_font(&joined(&main));
    let f = flow(fonts, main, &font, content_w, line_h, align, rtl);
    for w in f.words {
        block.words.push(Word { pos: w.pos + vec2(0.0, y), ..w });
    }
    block.groups = f.groups;
    y += f.height;
    if bg.iter().any(|t| !t.space) {
        y += em * 0.1;
        let base = block.words.len();
        let g0 = block.groups.len();
        let font = cfg.bg_font(&joined(&bg));
        let f = flow(fonts, bg, &font, content_w, line_h, align, rtl);
        for w in f.words {
            block.words.push(Word { pos: w.pos + vec2(0.0, y), group: w.group + g0, ..w });
        }
        block.groups.extend(f.groups.into_iter().map(|g| Group { first: g.first + base, words: g.words.start + base..g.words.end + base, ..g }));
        y += f.height;
    }
    let gap = em / 6.0;
    if let Some(r) = roman {
        y += gap;
        let pad = vec2(cfg.rem() * 0.75, cfg.rem() * 0.25);
        let inner_w = content_w - pad.x * 2.0 - 2.0;
        let (body, inner) = match r {
            Roman::Timed(parts, text) => {
                let toks: Vec<Tok> = parts.iter().flat_map(split_part).collect();
                let font = cfg.roman_font(text);
                let lh = fonts.row_height(&font);
                let f = flow(fonts, toks, &font, inner_w, lh, Align::Left, false);
                (DecoBody::Timed { words: f.words, groups: f.groups }, vec2(f.width, f.height))
            }
            Roman::Text(t) => {
                let font = cfg.roman_font(t);
                let g = fonts.layout(t.to_owned(), font.clone(), Color32::WHITE, inner_w);
                (DecoBody::Text(t.to_owned(), font), g.size())
            }
        };
        let size = inner + pad * 2.0 + vec2(2.0, 2.0);
        let x = match align {
            Align::Left => 0.0,
            Align::Right => content_w - size.x,
            Align::Center => (content_w - size.x) * 0.5,
        };
        let rect = Rect::from_min_size(pos2(x, y), size);
        let body = match body {
            DecoBody::Timed { words, groups } => DecoBody::Timed { words: words.into_iter().map(|w| Word { pos: w.pos + rect.min.to_vec2() + pad + vec2(1.0, 1.0), ..w }).collect(), groups },
            b => b,
        };
        block.decos.push(Deco { body, rect, pill: true });
        y += size.y;
    }
    if let Some(t) = trans {
        y += gap;
        let font = cfg.trans_font(t);
        let g = fonts.layout(t.to_owned(), font.clone(), Color32::WHITE, content_w);
        let x = match align {
            Align::Left => 0.0,
            Align::Right => content_w - g.size().x,
            Align::Center => (content_w - g.size().x) * 0.5,
        };
        block.decos.push(Deco { body: DecoBody::Text(t.to_owned(), font), rect: Rect::from_min_size(pos2(x, y), g.size()), pill: false });
        y += g.size().y;
    }
    block.height = y + cfg.padding;
    let inset = match align {
        Align::Left => 0.0,
        Align::Right => full_w - content_w,
        Align::Center => (full_w - content_w) * 0.5,
    };
    if inset > 0.0 {
        let dx = vec2(inset, 0.0);
        for w in &mut block.words {
            w.pos += dx;
        }
        for d in &mut block.decos {
            d.rect = d.rect.translate(dx);
            if let DecoBody::Timed { words, .. } = &mut d.body {
                for w in words {
                    w.pos += dx;
                }
            }
        }
    }
    block
}

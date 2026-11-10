(() => {
  const SOURCE = 'https://raw.githubusercontent.com/bxanedot/nuvx/main/CHANGELOG.md';
  const root = document.getElementById('changelog');
  if (!root) return;

  const safeUrl = (u) => (/^https?:\/\//i.test(u) ? u : null);
  const attr = (tag, name) => {
    const m = new RegExp(`\\b${name}\\s*=\\s*["']([^"']*)["']`, 'i').exec(tag);
    return m ? m[1] : '';
  };

  const normalize = (md) =>
    md
      .replace(/<img\b[^>]*>/gi, (t) => ` ![${attr(t, 'alt')}](${attr(t, 'src')}) `)
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/?(p|div|center|picture|span|details|summary)\b[^>]*>/gi, '')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&');

  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  };

  const picture = (url, alt) => {
    const src = safeUrl(url);
    if (!src) return document.createTextNode(alt);
    const img = el('img');
    img.src = src;
    img.alt = alt;
    img.loading = 'lazy';
    img.decoding = 'async';
    return img;
  };

  const inline = (parent, text) => {
    const re = /(\*\*|__)(.+?)\1|`([^`]+)`|!\[([^\]]*)\]\(([^)\s]+)[^)]*\)|\[([^\]]+)\]\(([^)\s]+)[^)]*\)|\n/g;
    let last = 0;
    for (let m; (m = re.exec(text)); last = re.lastIndex) {
      if (m.index > last) parent.append(text.slice(last, m.index));
      if (m[2] != null) inline(parent.appendChild(el('strong')), m[2]);
      else if (m[3] != null) parent.append(el('code', null, m[3]));
      else if (m[5] != null) parent.append(picture(m[5], m[4]));
      else if (m[7] != null) {
        const href = safeUrl(m[7]);
        if (!href) parent.append(m[6]);
        else {
          const a = el('a');
          a.href = href;
          inline(a, m[6]);
          parent.append(a);
        }
      } else parent.append(el('br'));
    }
    if (last < text.length) parent.append(text.slice(last));
    return parent;
  };

  const onlyPictures = (line) => /^(\s*!\[[^\]]*\]\([^)]*\)\s*)+$/.test(line);
  const cells = (line) => line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim());

  const render = (md) => {
    const frag = document.createDocumentFragment();
    let para = [];
    let list = null;
    let table = null;
    let pictures = null;
    const flush = () => {
      if (para.length) frag.append(inline(el('p'), para.join('\n')));
      para = [];
    };
    const close = () => {
      flush();
      list = table = pictures = null;
    };
    for (const raw of normalize(md).split(/\r?\n/)) {
      const t = raw.trim();
      if (!t) {
        close();
        continue;
      }
      let m;
      if (onlyPictures(t)) {
        flush();
        list = table = null;
        if (!pictures) frag.append((pictures = el('div', 'cl-pictures')));
        for (const p of t.matchAll(/!\[([^\]]*)\]\(([^)\s]+)[^)]*\)/g)) pictures.append(picture(p[2], p[1]));
        continue;
      }
      pictures = null;
      if (t.startsWith('|')) {
        flush();
        list = null;
        const row = cells(t);
        if (row.every((c) => /^:?-+:?$/.test(c))) continue;
        const head = !table;
        if (head) frag.append(el('div', 'cl-table')).append((table = el('table')));
        const tr = table.appendChild(el('tr'));
        for (const c of row) inline(tr.appendChild(el(head ? 'th' : 'td')), c);
      } else if (/^(-{3,}|\*{3,}|_{3,})$/.test(t)) {
        close();
        frag.append(el('hr'));
      } else if ((m = /^(#{1,6})\s+(.*)$/.exec(t))) {
        close();
        if (m[1].length === 1) continue;
        frag.append(inline(el(`h${m[1].length}`), m[2]));
      } else if ((m = /^>\s?(.*)$/.exec(t))) {
        close();
        frag.append(inline(el('blockquote'), m[1]));
      } else if ((m = /^(?:[-*+]|(\d{1,3})\.)\s+(.*)$/.exec(t))) {
        flush();
        table = null;
        const tag = m[1] ? 'ol' : 'ul';
        if (!list || list.tagName.toLowerCase() !== tag) frag.append((list = el(tag)));
        inline(list.appendChild(el('li')), m[2]);
      } else if (list && /^\s/.test(raw) && !para.length) {
        const li = list.lastElementChild;
        li.append(' ');
        inline(li, t);
      } else {
        list = table = null;
        para.push(t);
      }
    }
    close();
    return frag;
  };

  fetch(SOURCE, { cache: 'no-cache' })
    .then((r) => (r.ok ? r.text() : Promise.reject(new Error(String(r.status)))))
    .then((md) => root.replaceChildren(render(md)))
    .catch(() => {
      root.replaceChildren(el('p', 'cl-status'));
      const p = root.firstChild;
      p.append("The changelog couldn't load. Read it on ");
      const a = el('a', null, 'GitHub');
      a.href = 'https://github.com/bxanedot/nuvx/blob/main/CHANGELOG.md';
      p.append(a, '.');
    })
    .finally(() => root.setAttribute('aria-busy', 'false'));
})();

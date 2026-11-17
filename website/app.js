'use strict';
const toggle = document.querySelector('.menu-toggle');
const nav = document.querySelector('.main-nav');
if (toggle && nav) {
  toggle.addEventListener('click', () => {
    const open = toggle.getAttribute('aria-expanded') === 'true';
    toggle.setAttribute('aria-expanded', String(!open));
    toggle.setAttribute('aria-label', open ? 'Открыть меню' : 'Закрыть меню');
    nav.classList.toggle('is-open', !open);
  });
  nav.querySelectorAll('a').forEach(link => link.addEventListener('click', () => {
    nav.classList.remove('is-open');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', 'Открыть меню');
  }));
}
const year = document.querySelector('#year');
if (year) year.textContent = String(new Date().getFullYear());
// В скриншоте VK нет ссылки сообщества. Не подменяем неизвестный адрес выдуманным.
const community = document.querySelector('[data-vk-placeholder]');
if (community) {
  community.addEventListener('click', e => {
    e.preventDefault();
    alert('Ссылка на сообщество LUMQERA будет добавлена после уточнения точного адреса.');
  });
}

// Read-only ranking UI. Data is produced by LumqeraCore and manually synchronized to /data.
const ratingResults = document.querySelector('#rating-results');
if (ratingResults) {
  const ratingTabs = Array.from(document.querySelectorAll('[data-rating-tab]'));
  const ratingUpdated = document.querySelector('#rating-updated');
  const ratingHeader = document.querySelector('#ranking-header');
  const refresh = document.querySelector('#refresh-rating');
  let dataset = null;
  let current = 'clans';

  function readableDate(iso) {
    if (typeof iso !== 'string' || !Number.isFinite(Date.parse(iso))) return 'Статистика ещё не опубликована';
    return 'Последнее обновление: ' + new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
  }
  function cell(text, css = '') {
    const el = document.createElement('span');
    if (css) el.className = css;
    el.textContent = String(text);
    return el;
  }
  function renderOutposts() {
    const container = document.querySelector('#outpost-results');
    if (!container) return;
    container.replaceChildren();
    const outposts = Array.isArray(dataset?.outposts) ? dataset.outposts : [];
    if (!outposts.length) {
      const empty = document.createElement('p');
      empty.className = 'empty-rating';
      empty.textContent = 'Контрольные точки ещё не добавлены на сервер.';
      container.append(empty);
      return;
    }
    outposts.slice(0, 20).forEach(point => {
      if (!point || typeof point.name !== 'string') return;
      const entry = document.createElement('div');
      entry.className = 'rating-row';
      entry.append(cell(point.name, 'rating-primary'), cell(point.owner || 'Нейтральная'), cell(point.owner ? 'Захвачена' : 'Свободна', 'rating-score'));
      container.append(entry);
    });
  }
  function render() {
    ratingResults.replaceChildren();
    const isClans = current === 'clans';
    ratingHeader.replaceChildren(...(isClans ? ['МЕСТО / КЛАН', 'ИГРОКИ / ЧАНКИ', 'ВЛИЯНИЕ'] : ['МЕСТО / ИГРОК', 'СМЕРТЕЙ', 'УБИЙСТВ']).map(text => cell(text)));
    const rows = dataset && Array.isArray(dataset[current]) ? dataset[current].slice(0, 10) : [];
    ratingUpdated.textContent = readableDate(dataset?.updatedAt);
    renderOutposts();
    if (!rows.length) {
      const empty = document.createElement('p');
      empty.className = 'empty-rating';
      empty.textContent = 'Пока нет опубликованных результатов. Рейтинг появится после игровых тестов.';
      ratingResults.append(empty);
      return;
    }
    rows.forEach((row, index) => {
      if (!row || typeof row.name !== 'string') return;
      const entry = document.createElement('div');
      entry.className = 'rating-row';
      const nameCell = document.createElement('span');
      nameCell.className = 'rating-primary';
      nameCell.append(cell(index + 1, 'rating-rank'), cell(row.name));
      if (isClans) nameCell.append(cell(' · ' + String(row.leader || '—') + ' · Побед: ' + String(row.wins ?? 0), 'rating-sub'));
      entry.append(nameCell, cell(isClans ? (String(row.members ?? 0) + ' / ' + String(row.claims ?? 0)) : (row.deaths ?? 0)), cell(isClans ? (row.influence ?? 0) : (row.kills ?? 0), 'rating-score'));
      ratingResults.append(entry);
    });
  }
  async function loadRating() {
    refresh.disabled = true;
    try {
      const response = await fetch('./data/public-stats.json', { cache: 'no-store' });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const parsed = await response.json();
      if (!parsed || parsed.schemaVersion !== 1 || !Array.isArray(parsed.clans) || !Array.isArray(parsed.players)) throw new Error('Несовместимый формат');
      dataset = parsed;
      render();
    } catch (error) {
      dataset = null;
      render();
      const empty = ratingResults.querySelector('.empty-rating');
      if (empty) empty.textContent = 'Не удалось загрузить статистику. Попробуйте обновить страницу.';
    } finally { refresh.disabled = false; }
  }
  ratingTabs.forEach(tab => tab.addEventListener('click', () => {
    current = tab.dataset.ratingTab;
    ratingTabs.forEach(item => {
      item.classList.toggle('is-active', item === tab);
      item.setAttribute('aria-pressed', String(item === tab));
    });
    render();
  }));
  refresh.addEventListener('click', loadRating);
  loadRating();
}

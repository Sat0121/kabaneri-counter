/* アプリ版で「台選び」（公開中の asabu-kabaneri サイト）を表示したときに足す部品。
   ・ヘッダーの左端に「カウンターに戻る」ボタン
   ・通信できず保存済みの内容を出しているときの案内
   ・ステータスバーとナビゲーションバーの色をページに合わせる
   サイト側の作りが変わってヘッダーが見つからなくても、戻るボタンは画面の隅に出す。 */
(function (info) {
  var S = window.KabaneriShell;
  if (!S || window.__kbShell) return;
  window.__kbShell = true;

  var css = document.createElement('style');
  css.textContent =
    '.kb-back{width:48px;height:48px;border:0;border-radius:999px;background:transparent;display:grid;place-items:center;cursor:pointer;flex:none;' +
    'color:var(--md-sys-color-on-surface,#191c20);-webkit-tap-highlight-color:transparent}' +
    '.kb-back:active{background:color-mix(in srgb,currentColor 12%,transparent)}' +
    '.kb-back svg{width:24px;height:24px;fill:currentColor}' +
    '.kb-back.kb-float{position:fixed;z-index:60;left:max(8px,env(safe-area-inset-left));top:calc(8px + env(safe-area-inset-top));' +
    'background:var(--md-sys-color-surface-container-high,#e7e8ee);box-shadow:0 1px 3px rgba(0,0,0,.3)}' +
    '.kb-offline{display:flex;gap:8px;align-items:center;margin:8px 0 4px;padding:8px 12px;border-radius:12px;font-size:12.5px;line-height:1.45;' +
    'background:var(--md-sys-color-tertiary-container,#ffdcc1);color:var(--md-sys-color-on-tertiary-container,#2e1500)}';
  document.head.appendChild(css);

  var bar = document.getElementById('appbar');
  var btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'kb-back';
  btn.setAttribute('aria-label', 'カウンターに戻る');
  btn.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>';
  btn.addEventListener('click', function () { S.back(); });
  if (bar) {
    bar.insertBefore(btn, bar.firstChild);
    bar.style.paddingLeft = 'max(4px, env(safe-area-inset-left))';
  } else {
    btn.classList.add('kb-float');
    document.body.appendChild(btn);
  }

  if (info.offline) {
    var n = document.createElement('div');
    n.className = 'kb-offline';
    n.setAttribute('role', 'status');
    var when = S.savedAt ? S.savedAt() : '';
    n.textContent = '通信できないため、' + (when ? when + ' に' : '前回') + '取得した内容を表示しています。通信できるようになると最新に切り替わります。';
    var main = document.querySelector('main') || document.body;
    main.insertBefore(n, main.firstChild);
  }

  function hex(c) {
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c || '');
    if (!m) return /^#[0-9a-f]{6}$/i.test(c || '') ? c : null;
    return '#' + [m[1], m[2], m[3]].map(function (x) { return ('0' + (+x).toString(16)).slice(-2); }).join('');
  }
  function send() {
    try {
      var meta = document.querySelector('meta[name="theme-color"]');
      var top = (bar && hex(getComputedStyle(bar).backgroundColor)) || (meta && hex(meta.content)) || hex(getComputedStyle(document.body).backgroundColor) || '#ffffff';
      var nb = document.querySelector('.navbar');
      var bottom = (nb && hex(getComputedStyle(nb).backgroundColor)) || top;
      S.bars(top, bottom);
    } catch (e) { /* 色合わせできなくても表示は続ける */ }
  }
  window.__kbShellSend = send;
  send();
  setTimeout(send, 300); // ヘッダーの色は少しかけて切り替わるので、落ち着いてからもう一度合わせる
  if (bar) bar.addEventListener('transitionend', send);
  var later = function () { setTimeout(send, 260); };
  var meta = document.querySelector('meta[name="theme-color"]');
  if (meta) new MutationObserver(later).observe(meta, { attributes: true });
  if (bar) new MutationObserver(later).observe(bar, { attributes: true, attributeFilter: ['class'] });
  if (window.matchMedia) { try { window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', later); } catch (e) {} }
})(__INFO__);

(function () {
  if (window.__jetSniff) return;
  window.__jetSniff = 1;
  function send(o) { try { jet.postMessage(JSON.stringify(o)); } catch (e) {} }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return '' + u; } }
  function rep(u, k) { send({ u: abs(u), k: k, r: location.href }); }
  function check(u, t) {
    if (typeof t !== 'string') return;
    t = t.replace(/^﻿/, '').replace(/^\s+/, '');
    if (t.indexOf('#EXTM3U') === 0) rep(u, /#EXT-X-STREAM-INF/.test(t) ? 'master' : 'm3u8');
    else if (t.indexOf('<MPD') >= 0 || (t.indexOf('<?xml') === 0 && t.indexOf('MPD') > 0)) rep(u, 'mpd');
  }
  // XHR ile yüklenen video listeleri
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__jetU = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var x = this;
    x.addEventListener('load', function () {
      try {
        if (x.responseType === '' || x.responseType === 'text') check(x.responseURL || x.__jetU, x.responseText.slice(0, 400));
      } catch (e) {}
    });
    return xs.apply(this, arguments);
  };
  // fetch ile yüklenen video listeleri
  var f = window.fetch;
  if (f) window.fetch = function (i) {
    return f.apply(this, arguments).then(function (r) {
      try {
        var u = r.url || (i && i.url) || i;
        var len = parseInt(r.headers.get('content-length') || '0', 10);
        var ct = r.headers.get('content-type') || '';
        if (len < 400000 && !/video|audio|image|font|css|javascript|html/i.test(ct))
          r.clone().text().then(function (t) { check(u, t.slice(0, 400)); }).catch(function () {});
      } catch (e) {}
      return r;
    });
  };
  // doğrudan dosya oynatan videolar ve oynatma konumu
  setInterval(function () {
    var v = document.querySelectorAll('video');
    for (var i = 0; i < v.length; i++) {
      var e = v[i];
      var s = e.currentSrc || e.src;
      if (s && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0 && e.__jetS !== s) { e.__jetS = s; rep(s, 'file'); }
      if (!e.paused && e.currentTime > 1 && e.duration > 60) send({ t: e.currentTime });
    }
  }, 2000);
})();

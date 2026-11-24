(function () {
  if (window.__jetSniff) return;
  window.__jetSniff = 1;
  function send(o) { try { jet.postMessage(JSON.stringify(o)); } catch (e) {} }
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return '' + u; } }
  function rep(u, k, d) { send({ u: abs(u), k: k, r: location.href, d: d || -1 }); }
  function check(u, t) {
    if (typeof t !== 'string') return;
    t = t.split(String.fromCharCode(65279)).join('').replace(/^\s+/, '');
    if (t.indexOf('#EXTM3U') === 0) {
      if (/#EXT-X-STREAM-INF/.test(t)) { rep(u, 'master'); return; }
      // toplam süre: reklamları ayırmak için
      var d = 0, m, re = /#EXTINF:\s*([\d.]+)/g;
      while ((m = re.exec(t))) d += parseFloat(m[1]) || 0;
      rep(u, 'm3u8', /#EXT-X-ENDLIST/.test(t) ? d : -1);
    } else if (t.indexOf('<MPD') >= 0 || (t.indexOf('<?xml') === 0 && t.indexOf('MPD') > 0)) rep(u, 'mpd');
  }
  // XHR ile yüklenen video listeleri
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (m, u) { this.__jetU = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var x = this;
    x.addEventListener('load', function () {
      try {
        if (x.responseType === '' || x.responseType === 'text') {
          var t = x.responseText;
          check(x.responseURL || x.__jetU, t.length < 2000000 ? t : t.slice(0, 400));
        }
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
        if (len < 2000000 && !/video|audio|image|font|css|javascript|html/i.test(ct))
          r.clone().text().then(function (t) { check(u, t); }).catch(function () {});
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
      if (s && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0 && isFinite(e.duration) && e.duration > 0 && e.__jetS !== s) {
        e.__jetS = s; rep(s, 'file', e.duration);
      }
      if (!e.paused && e.currentTime > 1 && e.duration > 60) send({ t: e.currentTime });
    }
  }, 2000);
})();

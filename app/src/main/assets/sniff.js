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
    } else if (t.indexOf('WEBVTT') === 0 || /^\d+\s*\r?\n\d\d:\d\d:\d\d[,.]\d+\s*-->/.test(t)) {
      send({ s: abs(u), r: location.href });
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
  function p2(n, w) { n = '' + n; while (n.length < w) n = '0' + n; return n; }
  function ts(s) {
    var ms = Math.round(s * 1000), h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, sec = Math.floor(ms / 1000) % 60;
    return p2(h, 2) + ':' + p2(m, 2) + ':' + p2(sec, 2) + '.' + p2(ms % 1000, 3);
  }
  // doğrudan dosya oynatan videolar ve oynatma konumu
  setInterval(function () {
    var tr = document.querySelectorAll('track');
    for (var j = 0; j < tr.length; j++) {
      var k = tr[j];
      if (k.src && !k.__jetT && (k.kind === 'subtitles' || k.kind === 'captions' || !k.kind)) {
        k.__jetT = 1; send({ s: abs(k.src), l: k.label || k.srclang || '', r: location.href });
      }
    }
    var v = document.querySelectorAll('video');
    for (var i = 0; i < v.length; i++) {
      var e = v[i];
      var s = e.currentSrc || e.src;
      if (s && s.indexOf('blob:') !== 0 && s.indexOf('data:') !== 0 && isFinite(e.duration) && e.duration > 0 && e.__jetS !== s) {
        e.__jetS = s; rep(s, 'file', e.duration);
      }
      if (!e.paused && e.currentTime > 1 && e.duration > 60) send({ t: e.currentTime });
      // oynatıcının videoya eklediği altyazı satırlarını doğrudan oku
      var tt = e.textTracks || [];
      for (var q = 0; q < tt.length; q++) {
        var T = tt[q];
        var cs = null;
        try { cs = T.cues; } catch (x) {}
        if (!cs || !cs.length || T.__jetN === cs.length) continue;
        T.__jetN = cs.length;
        var out = 'WEBVTT\n\n';
        for (var c = 0; c < cs.length; c++) {
          var cu = cs[c];
          out += ts(cu.startTime) + ' --> ' + ts(cu.endTime) + '\n' + (cu.text || '') + '\n\n';
        }
        send({ vtt: out, l: T.label || T.language || ('Altyazı ' + (q + 1)), r: location.href });
      }
    }
  }, 2000);
})();

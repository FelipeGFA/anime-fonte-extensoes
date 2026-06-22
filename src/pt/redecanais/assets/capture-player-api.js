(function() {
  if (window.__rcPlayerApiSnifferRunning) return;
  window.__rcPlayerApiSnifferRunning = true;

  var runToken = window.__rcPlayerApiSnifferToken || 0;
  var started = Date.now();
  var marker = "player3/rc-player/player/dist/jquery.videojs.4.5.2.api";
  var mainVideoConst = "VIDEO_URL_POST_BASE64_VkNfU0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS";
  var alternateVideoConst = "VIDEO_URL_POST_BASE64_SEVfU0VfRlVERVVfT1RBUklPX1ZBX1BST0NVUkFSX0VNX09VVFJPX0xVR0FS";

  function absolute(value) {
    try {
      return value ? new URL(value, document.baseURI).href : "";
    } catch (error) {
      return value || "";
    }
  }

  function unescapePart(value) {
    return value
      .replace(/\\x([0-9a-fA-F]{2})/g, function(_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
      .replace(/\\u([0-9a-fA-F]{4})/g, function(_, hex) { return String.fromCharCode(parseInt(hex, 16)); })
      .replace(/\\(["'\\/])/g, function(_, chr) { return chr; })
      .replace(/\\n/g, "\n")
      .replace(/\\r/g, "\r")
      .replace(/\\t/g, "\t");
  }

  function joinStringLiterals(expression) {
    var output = "";
    var match;
    var regex = /(["'])((?:\\.|(?!\1)[^\\])*)\1/g;
    while ((match = regex.exec(expression))) {
      output += unescapePart(match[2]);
    }
    return output;
  }

  function extractApi(script) {
    var markerIndex = script.indexOf(marker);
    if (markerIndex < 0) return "";

    var equalsIndex = script.lastIndexOf("=", markerIndex);
    var endIndex = script.indexOf(";", markerIndex);
    if (equalsIndex < 0 || endIndex < 0) return "";

    var joined = joinStringLiterals(script.slice(equalsIndex + 1, endIndex));
    return joined.indexOf(marker) >= 0 ? joined : "";
  }

  function extractVideoUrl(decoded) {
    var names = [mainVideoConst, alternateVideoConst];
    for (var i = 0; i < names.length; i++) {
      var regex = new RegExp("const\\s+" + names[i] + "\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\")");
      var match = regex.exec(decoded);
      if (!match) continue;

      try {
        return JSON.parse(match[1]);
      } catch (error) {
        return unescapePart(match[1].slice(1, -1));
      }
    }
    return "";
  }

  function decodeApiBody(body) {
    var stage1 = atob((body || "").trim());
    var match = /var\s+c\s*=\s*\[([0-9,\s]+)\]/.exec(stage1);
    if (!match) return "";

    var parts = match[1].split(",");
    var decoded = "";
    for (var i = 0; i < parts.length; i++) {
      decoded += String.fromCharCode(parseInt(parts[i], 10));
    }

    return extractVideoUrl(decoded);
  }

  function pass(iframe, video) {
    window.__rcPlayerApiSnifferRunning = false;
    window.PlayerApiSniffer.passResult(runToken, iframe || "", video || "");
  }

  function fetchApi(iframeWindow, iframeUrl, apiUrl) {
    var fetcher = iframeWindow && iframeWindow.fetch ? iframeWindow.fetch.bind(iframeWindow) : fetch;
    fetcher(apiUrl, {
      method: "GET",
      credentials: "include",
      cache: "no-store",
      referrer: iframeUrl,
      headers: { "Accept": "*/*" }
    }).then(function(response) {
      return response.text();
    }).then(function(body) {
      pass(iframeUrl, decodeApiBody(body));
    }, function(error) {
      pass(iframeUrl, "");
    });
  }

  function tick() {
    var iframe = document.querySelector('iframe[name="Player"], iframe[src*="/player3/server.php"], iframe[src*="player3/server.php"]');
    var iframeUrl = iframe ? (iframe.getAttribute("src") || iframe.src || "") : "";

    try {
      if (iframe) {
        var doc = iframe.contentDocument || (iframe.contentWindow && iframe.contentWindow.document);
        var scripts = doc ? Array.prototype.slice.call(doc.querySelectorAll("script:not([src])")) : [];
        for (var i = 0; i < scripts.length; i++) {
          var script = scripts[i].text || scripts[i].textContent || scripts[i].innerHTML || "";
          var api = extractApi(script);
          if (api) {
            fetchApi(iframe.contentWindow, absolute(iframeUrl), absolute(api));
            return;
          }
        }
      }
    } catch (error) {
    }

    if (Date.now() - started > 12000) {
      pass(absolute(iframeUrl), "");
      return;
    }

    setTimeout(tick, 250);
  }

  tick();
})();

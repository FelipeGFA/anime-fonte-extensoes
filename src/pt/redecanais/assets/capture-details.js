(function() {
  var started = Date.now();

  function attr(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/"/g, "&quot;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function hasDetails() {
    var description = document.querySelector('div[itemprop="description"], div.pm-category-description');
    var player = document.querySelector('iframe[name="Player"], iframe[src*="/player3/"]');
    if (player) return true;
    if (!description) return false;
    return !!description.querySelector('a[href]') ||
      ((description.textContent || "").trim().length > 80);
  }

  function minimalHtml() {
    var head = ['<base href="' + attr(location.href) + '">'];
    if (document.title) head.push('<title>' + attr(document.title) + '</title>');
    document.querySelectorAll('meta[name], meta[property], meta[itemprop]').forEach(function(meta) {
      head.push(meta.outerHTML);
    });

    var body = [];
    var title = document.querySelector('h1[itemprop="name"], h1');
    var description = document.querySelector('div[itemprop="description"], div.pm-category-description');
    var player = document.querySelector('iframe[name="Player"], iframe[src*="/player3/"]');
    if (title) body.push(title.outerHTML);
    if (description) body.push(description.outerHTML);
    if (player) body.push('<div id="video-wrapper">' + player.outerHTML + '</div>');

    return '<!doctype html><html><head>' + head.join('') + '</head><body>' + body.join('') + '</body></html>';
  }

  function capture() {
    if (location.href === "about:blank") {
      setTimeout(capture, 250);
      return;
    }

    if (hasDetails() || document.readyState === "complete" || Date.now() - started > 6000) {
      window.DetailsProxy.passHtml(
        document.contentType || "text/html",
        minimalHtml()
      );
      return;
    }

    setTimeout(capture, 250);
  }

  capture();
})();

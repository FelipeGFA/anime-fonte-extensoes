(function() {
  function sendImage(url) {
    fetch(url, { credentials: "include", cache: "force-cache" })
      .then(function(response) {
        if (!response.ok) throw new Error("thumb-http-" + response.status);
        return response.blob();
      })
      .then(function(blob) {
        var reader = new FileReader();
        reader.onloadend = function() {
          var dataUrl = String(reader.result || "");
          var comma = dataUrl.indexOf(",");
          window.SearchThumbProxy.passThumbImage(
            blob.type || "image/jpeg",
            comma >= 0 ? dataUrl.substring(comma + 1) : ""
          );
        };
        reader.onerror = function() {
          window.SearchThumbProxy.fail();
        };
        reader.readAsDataURL(blob);
      })
      .catch(function() {
        window.SearchThumbProxy.fail();
      });
  }

  function capture() {
    var meta = document.querySelector('meta[property="og:image"], meta[itemprop="thumbnailUrl"]');
    var image = meta || document.querySelector('.thumbnail .pm-video-thumb img, ul.pm-ul-browse-videos .pm-video-thumb img');
    var url = image ? (
      image.getAttribute("content") ||
      image.getAttribute("data-echo") ||
      image.getAttribute("data-src") ||
      image.getAttribute("src")
    ) : "";
    if (url) {
      sendImage(new URL(url, location.href).href);
      return;
    }

    if (document.readyState === "complete") {
      window.SearchThumbProxy.fail();
      return;
    }

    setTimeout(capture, 250);
  }

  capture();
})();

/**
 * Arena AI native download hook.
 *
 * arena.ai is a modern SPA. Workspace zips, generated images, and exported
 * chats are produced in JS as Blob / data: URLs and "downloaded" with a
 * synthetic <a download> click. Android WebView's DownloadListener receives
 * only the opaque blob: URL — it cannot resolve it — so we intercept here,
 * read the bytes, and hand base64 to ArenaNative.saveBlob.
 *
 * Idempotent: safe to re-inject on every onPageFinished / onProgressChanged.
 */
(function () {
  if (window.__arenaNativeDlHook) {
    return;
  }
  var bridge = window.ArenaNative;
  if (!bridge || typeof bridge.saveBlob !== "function") {
    console.log("[ArenaNative] bridge missing; hook not installed");
    return;
  }
  window.__arenaNativeDlHook = true;
  window.__arenaBlobs = window.__arenaBlobs || {};

  function guessName(name, mime) {
    if (name && String(name).trim()) {
      return String(name).trim();
    }
    var map = {
      "application/zip": "zip",
      "application/x-zip-compressed": "zip",
      "application/pdf": "pdf",
      "application/json": "json",
      "image/png": "png",
      "image/jpeg": "jpg",
      "image/webp": "webp",
      "image/gif": "gif",
      "text/plain": "txt",
      "text/markdown": "md",
      "text/csv": "csv",
      "text/html": "html"
    };
    var ext = (mime && map[mime]) || "";
    return ext ? "download." + ext : "download";
  }

  // Binder transactions are ~1 MB. 256 KB of base64 is well under the cap
  // and is a multiple of 4, so each chunk decodes independently.
  var CHUNK = 256 * 1024;

  function sendBase64(b64, type, name) {
    if (!b64) {
      return;
    }
    // Always chunk. typeof bridge.foo === "function" is unreliable on some
    // WebView versions (injected methods are not real JS functions).
    try {
      var id = "b" + Date.now() + "_" + Math.random().toString(36).slice(2, 10);
      console.log(
        "[ArenaNative] chunked send id=" +
          id +
          " chars=" +
          b64.length +
          " mime=" +
          type +
          " name=" +
          name
      );
      bridge.saveBlobBegin(id, type, name);
      var offset = 0;
      while (offset < b64.length) {
        bridge.saveBlobChunk(id, b64.substr(offset, CHUNK));
        offset += CHUNK;
      }
      bridge.saveBlobEnd(id);
    } catch (e) {
      console.log("[ArenaNative] chunked send failed, falling back: " + (e && e.message));
      try {
        bridge.saveBlob(b64, type, name);
      } catch (e2) {
        console.log("[ArenaNative] saveBlob fallback failed: " + (e2 && e2.message));
      }
    }
  }

  function postBlob(blob, fileName, mime) {
    try {
      var reader = new FileReader();
      reader.onloadend = function () {
        try {
          var result = reader.result || "";
          var text = String(result);
          var comma = text.indexOf(",");
          var b64 = comma >= 0 ? text.slice(comma + 1) : "";
          var type = (blob && blob.type) || mime || "application/octet-stream";
          console.log(
            "[ArenaNative] posting blob bytes~=" +
              (blob && blob.size) +
              " mime=" +
              type +
              " name=" +
              fileName
          );
          sendBase64(b64, type, guessName(fileName, type));
        } catch (e) {
          console.log("[ArenaNative] postBlob failed: " + (e && e.message));
        }
      };
      reader.readAsDataURL(blob);
    } catch (e) {
      console.log("[ArenaNative] FileReader failed: " + (e && e.message));
    }
  }

  function saveUrl(url, fileName, mime) {
    if (!url) {
      return;
    }
    if (url.indexOf("data:") === 0) {
      var m = url.match(/^data:([^;,]+)?((?:;[^,]*)*),([\s\S]*)$/);
      if (!m) {
        return;
      }
      var type = m[1] || mime || "application/octet-stream";
      var meta = m[2] || "";
      var data = m[3] || "";
      var b64 =
        meta.indexOf("base64") >= 0
          ? data
          : btoa(unescape(encodeURIComponent(data)));
      console.log("[ArenaNative] data: URL mime=" + type + " name=" + fileName);
      sendBase64(b64, type, guessName(fileName, type));
      return;
    }
    if (url.indexOf("blob:") === 0) {
      var remembered = window.__arenaBlobs[url];
      if (remembered) {
        postBlob(remembered, fileName, mime || remembered.type);
        return;
      }
      fetch(url)
        .then(function (r) {
          return r.blob();
        })
        .then(function (blob) {
          postBlob(blob, fileName, mime || blob.type);
        })
        .catch(function (e) {
          console.log("[ArenaNative] fetch(blob) failed: " + (e && e.message));
        });
    }
  }

  try {
    var origCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (obj) {
      var u = origCreate(obj);
      try {
        if (obj && typeof Blob !== "undefined" && obj instanceof Blob) {
          window.__arenaBlobs[u] = obj;
        }
      } catch (_) {}
      return u;
    };
  } catch (_) {}

  function isDownloadableAnchor(a) {
    if (!a) {
      return false;
    }
    var href = a.href || a.getAttribute("href") || "";
    if (!a.hasAttribute("download")) {
      return false;
    }
    return href.indexOf("blob:") === 0 || href.indexOf("data:") === 0;
  }

  function interceptAnchor(a) {
    var href = a.href || a.getAttribute("href") || "";
    var name = a.getAttribute("download") || "";
    var mime = a.getAttribute("type") || "";
    console.log("[ArenaNative] intercept <a download> href=" + href.slice(0, 48) + " name=" + name);
    saveUrl(href, name, mime);
  }

  document.addEventListener(
    "click",
    function (ev) {
      var t = ev.target;
      var a = t && t.closest ? t.closest("a") : null;
      if (isDownloadableAnchor(a)) {
        ev.preventDefault();
        ev.stopPropagation();
        interceptAnchor(a);
      }
    },
    true
  );

  try {
    var proto = HTMLAnchorElement.prototype;
    var origClick = proto.click;
    proto.click = function () {
      if (isDownloadableAnchor(this)) {
        interceptAnchor(this);
        return;
      }
      return origClick.apply(this, arguments);
    };
  } catch (_) {}

  // window.open(blobUrl) would spawn a popup WebView that cannot resolve
  // a blob created in this document. Intercept and save instead.
  // OAuth (https://accounts.google.com/...) is left untouched.
  try {
    var origOpen = window.open;
    window.open = function (url, target, features) {
      var href = url == null ? "" : String(url);
      if (href.indexOf("blob:") === 0 || href.indexOf("data:") === 0) {
        console.log("[ArenaNative] intercept window.open(" + href.slice(0, 48) + ")");
        saveUrl(href, target || "", "");
        return null;
      }
      return origOpen.apply(this, arguments);
    };
  } catch (_) {}

  window.__arenaFetchBlob = function (url, fileName, mime) {
    console.log("[ArenaNative] __arenaFetchBlob url=" + String(url).slice(0, 64));
    saveUrl(url, fileName, mime);
  };

  console.log("[ArenaNative] download hook installed");
})();

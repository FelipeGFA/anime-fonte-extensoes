package eu.kanade.tachiyomi.animeextension.pt.redecanais.htmlproxy

internal const val HTML_BRIDGE_INTERFACE = "HtmlProxy"
internal const val HTML_PROXY_TIMEOUT_SECONDS = 30L
internal const val HTML_PROXY_CLEANUP_TIMEOUT_SECONDS = 25L
internal const val PAGE_FINISHED_HTML_CAPTURE_DELAY_MS = 100L
internal const val LISTING_READY_ITEM_COUNT = 48
internal const val LISTING_READY_MAX_ATTEMPTS = 80
internal const val LISTING_READY_POLL_DELAY_MS = 100L
internal const val OPEN_WEBVIEW_MESSAGE = "Abra a webview e espere o site carregar completamente"
internal const val LISTING_READY_SCRIPT = "document.querySelectorAll('ul.pm-ul-browse-videos > li').length"

internal val COOKIE_RELOAD_STATUS_CODES = setOf(402, 502, 504, 520)
internal val LISTING_BLOCKED_SCRIPT_HOSTS = setOf("ajax.googleapis.com", "netdna.bootstrapcdn.com")
internal val STATIC_EXTENSIONS = setOf(
    ".css",
    ".gif",
    ".ico",
    ".jpeg",
    ".jpg",
    ".js",
    ".map",
    ".png",
    ".svg",
    ".webp",
    ".woff",
    ".woff2",
)

package com.tonyt.magicpaste.domain

/**
 * Stands in for the device description until the page is served — the phone
 * knows its own name and Android version, this module does not.
 */
internal const val DEVICE_PLACEHOLDER = "__DEVICE__"

/**
 * Replaced with a link to the file manager, or with nothing when no [FileStore]
 * was supplied — an offer to browse files that 404s is worse than no offer.
 */
internal const val FILES_LINK_PLACEHOLDER = "__FILES_LINK__"

internal const val FILES_LINK_HTML =
    """<a class="jump" href="/files">Browse files on this device &rarr;</a>"""

/** The same, in reverse, on the file manager page. */
internal const val CLIPBOARD_LINK_PLACEHOLDER = "__CLIPBOARD_LINK__"

internal const val CLIPBOARD_LINK_HTML = """<a href="/">clipboard &rarr;</a>"""

/**
 * Escapes the few characters that would let text out of its element. Device
 * names are set by the user, so they are not to be trusted as markup.
 */
internal fun String.escapeHtml(): String = buildString(length) {
    for (character in this@escapeHtml) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(character)
        }
    }
}

/** Shared by the clipboard page and the PIN prompt, so the two look like one app. */
internal val PAGE_CSS: String = """
  :root {
    --bg: #f4f5f7;
    --card: #ffffff;
    --text: #16181d;
    --muted: #6b7280;
    --border: #dfe3e8;
    --accent: #4f46e5;
    --accent-text: #ffffff;
    --ok: #16a34a;
    --bad: #dc2626;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #0f1115;
      --card: #171a21;
      --text: #e8eaed;
      --muted: #9aa1ac;
      --border: #2a2f3a;
      --accent: #6d63ff;
      --accent-text: #ffffff;
      --ok: #34d399;
      --bad: #f87171;
    }
  }
  * { box-sizing: border-box; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  body {
    margin: 0;
    padding: 24px 16px 48px;
    background: var(--bg);
    color: var(--text);
    font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  }
  main { max-width: 720px; margin: 0 auto; }
  header {
    display: flex; align-items: center; gap: 10px;
    margin-bottom: 20px;
  }
  h1 { font-size: 22px; margin: 0; letter-spacing: -0.01em; }
  h2 { font-size: 13px; text-transform: uppercase; letter-spacing: 0.06em; color: var(--muted); margin: 0 0 10px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--muted); flex: none; }
  .dot.live { background: var(--ok); }
  .dot.down { background: var(--bad); }
  .meta {
    margin-left: auto;
    text-align: right;
    display: flex;
    flex-direction: column;
    line-height: 1.3;
    min-width: 0;
  }
  #device { font-size: 13px; }
  #status { color: var(--muted); font-size: 12px; }
  section {
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 16px;
  }
  textarea {
    width: 100%;
    min-height: 140px;
    resize: vertical;
    padding: 12px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--text);
    font: 14px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
  textarea:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
  .row { display: flex; gap: 8px; align-items: center; margin-top: 12px; flex-wrap: wrap; }
  button {
    appearance: none;
    border: 1px solid var(--border);
    background: var(--card);
    color: var(--text);
    padding: 9px 16px;
    border-radius: 8px;
    font-size: 15px;
    font-weight: 500;
    cursor: pointer;
  }
  button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-text); }
  button:active { transform: translateY(1px); }
  button:disabled { opacity: 0.5; cursor: default; }
  .hint { color: var(--muted); font-size: 13px; margin-left: auto; }
  /* The one link big enough to be the obvious next thing to tap. */
  a.jump {
    display: block;
    text-align: center;
    padding: 16px;
    margin-bottom: 16px;
    border-radius: 12px;
    background: var(--accent);
    color: var(--accent-text);
    font-size: 18px;
    font-weight: 600;
  }
  a.jump:hover { text-decoration: none; filter: brightness(1.08); }
  a.jump:active { transform: translateY(1px); }
  footer { color: var(--muted); font-size: 13px; text-align: center; margin-top: 24px; }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 1px 5px;
    font-size: 12px;
  }
""".trimIndent()

/**
 * The whole web client: one self-contained page, no external requests, because
 * the phone serving it usually has no route to the internet worth relying on.
 *
 * It long-polls `/api/clipboard?since=` so a copy on the phone shows up here
 * within a second without WebSockets.
 */
internal val WEB_UI_HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>MagicPaste</title>
<style>
$PAGE_CSS
</style>
</head>
<body>
<main>
  <header>
    <span id="dot" class="dot"></span>
    <h1>MagicPaste</h1>
    <span class="meta">
      <span id="device">__DEVICE__</span>
      <span id="status">connecting&hellip;</span>
    </span>
  </header>

  <section>
    <h2>Device clipboard</h2>
    <textarea id="incoming" readonly placeholder="Nothing on the device clipboard yet."></textarea>
    <div class="row">
      <button id="copy" class="primary" type="button">Copy to my clipboard</button>
      <span class="hint" id="copied"></span>
    </div>
  </section>

  <section>
    <h2>Send to device</h2>
    <textarea id="outgoing" placeholder="Type or paste here, then send it to the device clipboard."></textarea>
    <div class="row">
      <button id="send" class="primary" type="button">Send to device</button>
      <button id="clear" type="button">Clear</button>
      <span class="hint" id="sent"></span>
    </div>
  </section>

  __FILES_LINK__

  <footer>
    Also works from a terminal:
    <code>curl HOST/raw</code> &middot; <code>curl -d 'text' HOST/raw</code>
  </footer>
</main>

<script>
(function () {
  var incoming = document.getElementById('incoming');
  var outgoing = document.getElementById('outgoing');
  var dot = document.getElementById('dot');
  var status = document.getElementById('status');
  var copied = document.getElementById('copied');
  var sent = document.getElementById('sent');
  var revision = -1;

  document.querySelector('footer').innerHTML =
    document.querySelector('footer').innerHTML.split('HOST').join(location.origin);

  function setConnected(connected, message) {
    dot.className = 'dot ' + (connected ? 'live' : 'down');
    status.textContent = message;
  }

  function flash(node, message) {
    node.textContent = message;
    setTimeout(function () { node.textContent = ''; }, 2000);
  }

  function apply(snapshot) {
    revision = snapshot.revision;
    if (incoming.value !== snapshot.text) incoming.value = snapshot.text;
  }

  function poll() {
    fetch('/api/clipboard?since=' + revision, { cache: 'no-store' })
      .then(function (response) {
        // The session died — the device stopped sharing, or the PIN changed.
        if (response.status === 401) { location.reload(); throw new Error('unauthorized'); }
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .then(function (snapshot) {
        apply(snapshot);
        setConnected(true, 'live');
        poll();
      })
      .catch(function () {
        setConnected(false, 'disconnected, retrying...');
        setTimeout(poll, 2000);
      });
  }

  document.getElementById('copy').addEventListener('click', function () {
    var text = incoming.value;
    if (!text) { flash(copied, 'nothing to copy'); return; }
    var fallback = function () {
      // navigator.clipboard needs a secure context, which plain http on a LAN
      // is not, so the old selection-based path is the one that usually runs.
      incoming.removeAttribute('readonly');
      incoming.select();
      incoming.setSelectionRange(0, text.length);
      var ok = document.execCommand('copy');
      incoming.setAttribute('readonly', 'readonly');
      window.getSelection().removeAllRanges();
      flash(copied, ok ? 'copied' : 'press Ctrl+C / long-press to copy');
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { flash(copied, 'copied'); }, fallback);
    } else {
      fallback();
    }
  });

  document.getElementById('send').addEventListener('click', function () {
    var button = this;
    button.disabled = true;
    fetch('/api/clipboard', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: outgoing.value })
    })
      .then(function (response) {
        if (response.status === 401) { location.reload(); throw new Error('unauthorized'); }
        if (!response.ok) throw new Error('HTTP ' + response.status);
        return response.json();
      })
      .then(function (snapshot) { apply(snapshot); flash(sent, 'sent'); })
      .catch(function () { flash(sent, 'send failed'); })
      .then(function () { button.disabled = false; });
  });

  document.getElementById('clear').addEventListener('click', function () {
    outgoing.value = '';
    outgoing.focus();
  });

  poll();
})();
</script>
</body>
</html>
""".trimIndent()

/**
 * Served in place of the clipboard page to anyone without a session. Posting the
 * PIN to `/api/session` sets the cookie, after which a reload lands on the real
 * page — so the clipboard markup is never sent to an unauthenticated client.
 */
internal val LOGIN_HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>MagicPaste</title>
<style>
$PAGE_CSS
  main.login { max-width: 380px; margin: 12vh auto 0; }
  .lede { color: var(--muted); font-size: 14px; margin: 0 0 18px; }
  input[type="password"] {
    width: 100%;
    padding: 14px;
    border-radius: 8px;
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--text);
    font: 22px/1.2 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    letter-spacing: 0.35em;
    text-align: center;
  }
  input[type="password"]:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
  button.wide { width: 100%; margin-top: 12px; padding: 13px; }
  .error { color: var(--bad); font-size: 14px; min-height: 20px; margin-top: 12px; text-align: center; }
</style>
</head>
<body>
<main class="login">
  <header>
    <span class="dot"></span>
    <h1>MagicPaste</h1>
  </header>
  <section>
    <p class="lede">Enter the PIN shown in the MagicPaste app on the device.</p>
    <form id="form">
      <input id="pin" type="password" inputmode="numeric" autocomplete="off"
             autofocus placeholder="----" aria-label="PIN">
      <button id="submit" class="primary wide" type="submit">Unlock</button>
    </form>
    <div class="error" id="error"></div>
  </section>
</main>

<script>
(function () {
  var form = document.getElementById('form');
  var pin = document.getElementById('pin');
  var submit = document.getElementById('submit');
  var error = document.getElementById('error');

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    if (!pin.value) return;
    submit.disabled = true;
    error.textContent = '';
    fetch('/api/session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pin: pin.value })
    })
      .then(function (response) {
        if (response.ok) { location.reload(); return; }
        // A wrong PIN is answered slowly on purpose, and more slowly each time.
        error.textContent = response.status === 401 ? 'Wrong PIN.' : 'Something went wrong.';
        pin.value = '';
        pin.focus();
        submit.disabled = false;
      })
      .catch(function () {
        error.textContent = 'Could not reach the device.';
        submit.disabled = false;
      });
  });
})();
</script>
</body>
</html>
""".trimIndent()

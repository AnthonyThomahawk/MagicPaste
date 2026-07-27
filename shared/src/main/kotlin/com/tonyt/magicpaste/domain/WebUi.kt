package com.tonyt.magicpaste.domain

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
  #status { color: var(--muted); font-size: 13px; margin-left: auto; }
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
  footer { color: var(--muted); font-size: 13px; text-align: center; margin-top: 24px; }
  code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 1px 5px;
    font-size: 12px;
  }
</style>
</head>
<body>
<main>
  <header>
    <span id="dot" class="dot"></span>
    <h1>MagicPaste</h1>
    <span id="status">connecting&hellip;</span>
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

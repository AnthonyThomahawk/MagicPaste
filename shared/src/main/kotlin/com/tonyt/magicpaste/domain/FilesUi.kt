package com.tonyt.magicpaste.domain

/**
 * The file manager page, served at `/files`.
 *
 * Deliberately a second page rather than a tab on the clipboard view: the
 * clipboard page holds a long-poll open for its whole life, and pairing that
 * with file transfers on one page means two connections competing for the
 * phone's very limited concurrency.
 */
internal val FILES_HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>MagicPaste — Files</title>
<style>
$PAGE_CSS
  nav.crumbs {
    display: flex; flex-wrap: wrap; align-items: center; gap: 4px;
    font-size: 14px; margin-bottom: 12px; word-break: break-all;
  }
  nav.crumbs span.sep { color: var(--muted); }
  .toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 14px; }
  table { width: 100%; border-collapse: collapse; }
  th {
    text-align: left; font-size: 12px; text-transform: uppercase;
    letter-spacing: 0.05em; color: var(--muted); font-weight: 600;
    padding: 6px 8px; border-bottom: 1px solid var(--border);
  }
  td { padding: 9px 8px; border-bottom: 1px solid var(--border); vertical-align: middle; }
  tr:last-child td { border-bottom: none; }
  td.name { word-break: break-all; }
  td.size, th.size { text-align: right; white-space: nowrap; color: var(--muted); font-size: 13px; }
  td.when, th.when { white-space: nowrap; color: var(--muted); font-size: 13px; }
  td.actions { text-align: right; white-space: nowrap; }
  td.actions button { padding: 5px 10px; font-size: 13px; }
  .icon { display: inline-block; width: 20px; margin-right: 6px; }
  .empty { color: var(--muted); text-align: center; padding: 28px 0; }
  #drop {
    border: 2px dashed var(--border); border-radius: 12px;
    padding: 14px; text-align: center; color: var(--muted); font-size: 14px;
    margin-top: 14px;
  }
  #drop.over { border-color: var(--accent); color: var(--text); }
  #progress { font-size: 13px; color: var(--muted); margin-top: 8px; min-height: 18px; }
  .danger { color: var(--bad); }
  @media (max-width: 560px) {
    th.when, td.when { display: none; }
  }
</style>
</head>
<body>
<main>
  <header>
    <span class="dot live"></span>
    <h1>Files</h1>
    <span class="meta">
      <span id="device">__DEVICE__</span>
      <span id="status">__CLIPBOARD_LINK__</span>
    </span>
  </header>

  <section>
    <nav class="crumbs" id="crumbs"></nav>

    <div class="toolbar">
      <button id="up" type="button">Up</button>
      <button id="refresh" type="button">Refresh</button>
      <button id="mkdir" type="button">New folder</button>
      <button id="pick" class="primary" type="button">Upload</button>
      <input id="file" type="file" multiple hidden>
    </div>

    <table>
      <thead>
        <tr>
          <th>Name</th>
          <th class="size">Size</th>
          <th class="when">Modified</th>
          <th></th>
        </tr>
      </thead>
      <tbody id="rows"></tbody>
    </table>
    <div class="empty" id="empty" hidden>This folder is empty.</div>

    <div id="drop">Drop files here to upload</div>
    <div id="progress"></div>
  </section>
</main>

<script>
(function () {
  var rows = document.getElementById('rows');
  var crumbs = document.getElementById('crumbs');
  var empty = document.getElementById('empty');
  var progress = document.getElementById('progress');
  var drop = document.getElementById('drop');
  var picker = document.getElementById('file');
  var current = '/';
  var parent = null;

  function say(message) { progress.textContent = message; }

  function unauthorized(response) {
    if (response.status === 401) { location.reload(); return true; }
    return false;
  }

  function fail(response) {
    return response.text().then(function (message) {
      say(message || ('Failed with HTTP ' + response.status));
      throw new Error(message);
    });
  }

  function readable(bytes) {
    if (bytes < 1024) return bytes + ' B';
    var units = ['KB', 'MB', 'GB', 'TB'];
    var value = bytes / 1024;
    var index = 0;
    while (value >= 1024 && index < units.length - 1) { value = value / 1024; index++; }
    return (value >= 10 ? Math.round(value) : value.toFixed(1)) + ' ' + units[index];
  }

  function when(millis) {
    if (!millis) return '';
    var date = new Date(millis);
    return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  function render(listing) {
    current = listing.path;
    parent = listing.parent;
    document.getElementById('up').disabled = parent === null;

    crumbs.innerHTML = '';
    var walked = '';
    addCrumb('phone', '/');
    listing.path.split('/').forEach(function (segment) {
      if (!segment) return;
      walked = walked + '/' + segment;
      crumbs.appendChild(document.createTextNode(' '));
      var sep = document.createElement('span');
      sep.className = 'sep';
      sep.textContent = '/';
      crumbs.appendChild(sep);
      addCrumb(segment, walked);
    });

    rows.innerHTML = '';
    empty.hidden = listing.entries.length > 0;
    listing.entries.forEach(function (entry) { rows.appendChild(rowFor(entry)); });
    history.replaceState(null, '', '/files?at=' + encodeURIComponent(listing.path));
  }

  function addCrumb(label, path) {
    var link = document.createElement('a');
    link.href = '#';
    link.textContent = label;
    link.addEventListener('click', function (event) { event.preventDefault(); load(path); });
    crumbs.appendChild(link);
  }

  function pathOf(name) {
    return current === '/' ? '/' + name : current + '/' + name;
  }

  function rowFor(entry) {
    var row = document.createElement('tr');
    var path = pathOf(entry.name);

    var name = document.createElement('td');
    name.className = 'name';
    var icon = document.createElement('span');
    icon.className = 'icon';
    icon.textContent = entry.isDirectory ? '\u{1F4C1}' : '\u{1F4C4}';
    name.appendChild(icon);
    if (entry.isDirectory) {
      var link = document.createElement('a');
      link.href = '#';
      link.textContent = entry.name;
      link.addEventListener('click', function (event) { event.preventDefault(); load(path); });
      name.appendChild(link);
    } else {
      var download = document.createElement('a');
      download.href = '/api/files/download?path=' + encodeURIComponent(path);
      download.textContent = entry.name;
      name.appendChild(download);
    }
    row.appendChild(name);

    var size = document.createElement('td');
    size.className = 'size';
    size.textContent = entry.isDirectory ? '' : readable(entry.size);
    row.appendChild(size);

    var modified = document.createElement('td');
    modified.className = 'when';
    modified.textContent = when(entry.modified);
    row.appendChild(modified);

    var actions = document.createElement('td');
    actions.className = 'actions';
    actions.appendChild(button('Rename', function () { rename(entry, path); }));
    actions.appendChild(document.createTextNode(' '));
    var remove = button('Delete', function () { destroy(entry, path); });
    remove.className = 'danger';
    actions.appendChild(remove);
    row.appendChild(actions);

    return row;
  }

  function button(label, action) {
    var element = document.createElement('button');
    element.type = 'button';
    element.textContent = label;
    element.addEventListener('click', action);
    return element;
  }

  function load(path) {
    say('');
    return fetch('/api/files?path=' + encodeURIComponent(path), { cache: 'no-store' })
      .then(function (response) {
        if (unauthorized(response)) return null;
        if (!response.ok) return fail(response);
        return response.json();
      })
      .then(function (listing) { if (listing) render(listing); })
      .catch(function () { /* message already shown */ });
  }

  function post(url, body) {
    return fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (response) {
      if (unauthorized(response)) return null;
      if (!response.ok) return fail(response);
      return response.json();
    }).then(function (listing) { if (listing) render(listing); });
  }

  function rename(entry, path) {
    var name = prompt('Rename "' + entry.name + '" to:', entry.name);
    if (!name || name === entry.name) return;
    post('/api/files/rename', { path: path, name: name }).catch(function () {});
  }

  function destroy(entry, path) {
    var what = entry.isDirectory ? 'folder "' + entry.name + '" and everything in it' : '"' + entry.name + '"';
    if (!confirm('Delete ' + what + '? This cannot be undone.')) return;
    post('/api/files/delete', { path: path }).catch(function () {});
  }

  document.getElementById('up').addEventListener('click', function () {
    if (parent !== null) load(parent);
  });
  document.getElementById('refresh').addEventListener('click', function () { load(current); });
  document.getElementById('mkdir').addEventListener('click', function () {
    var name = prompt('Name of the new folder:');
    if (!name) return;
    post('/api/files/folder', { path: current, name: name }).catch(function () {});
  });

  document.getElementById('pick').addEventListener('click', function () { picker.click(); });
  picker.addEventListener('change', function () {
    upload(picker.files);
    picker.value = '';
  });

  function upload(files) {
    if (!files || !files.length) return;
    var form = new FormData();
    for (var index = 0; index < files.length; index++) {
      form.append('file', files[index], files[index].name);
    }
    say('Uploading ' + files.length + ' file(s)...');
    fetch('/api/files/upload?path=' + encodeURIComponent(current), { method: 'POST', body: form })
      .then(function (response) {
        if (unauthorized(response)) return null;
        if (!response.ok) return fail(response);
        return response.json();
      })
      .then(function (listing) {
        if (listing) { render(listing); say('Uploaded.'); }
      })
      .catch(function () {});
  }

  ['dragenter', 'dragover'].forEach(function (name) {
    drop.addEventListener(name, function (event) {
      event.preventDefault();
      drop.classList.add('over');
    });
  });
  ['dragleave', 'drop'].forEach(function (name) {
    drop.addEventListener(name, function (event) {
      event.preventDefault();
      drop.classList.remove('over');
    });
  });
  drop.addEventListener('drop', function (event) {
    upload(event.dataTransfer.files);
  });

  var opened = new URLSearchParams(location.search).get('at') || '/';
  load(opened);
})();
</script>
</body>
</html>
""".trimIndent()

# MagicPaste

Turns an Android device into a small web server on the local Wi-Fi network that
shares its clipboard and its files. Anyone on the same network who knows the PIN
opens `http://<device-ip>:8123` in a browser and can read what the phone copied,
push text back onto its clipboard, and browse, download, upload, rename and
delete files on the device.

## Using it

1. Open MagicPaste, choose what to share — **Clipboard**, **Files**, or both —
   and tap **Start sharing**. The port defaults to `8123` and the PIN is a random
   4 digits on first launch; everything is editable while stopped and remembered
   between launches.
2. The app lists every address the device can be reached at — usually one, Wi-Fi
   first. Open it on a laptop, tablet or another phone on the same network.
3. The browser asks for the PIN, then shows the device clipboard live, with a box
   to send text the other way.
4. For files, tap **Grant file access** in the app once, then follow *Browse files
   on this device* from the web page.

## What gets shared

Two switches in the app decide what the server offers, and only routes for what
is switched on are mounted — a disabled feature answers `404`, it is not merely
hidden. The choice is read when sharing starts, so changing it takes a stop and a
start; the switches are disabled while running rather than pretending otherwise.

Clipboard sharing is on by default and files are off, since files expose
considerably more. The bare address lands on whichever page exists: share only
files and `http://<device-ip>:8123` opens the file manager directly. With both
on, each page carries a link to the other.

## Files

The file manager at `/files` covers the device's shared storage — the volume
holding `DCIM`, `Download`, `Documents` and so on. It can list folders, download
files, upload (with drag and drop), create folders, rename, move and delete.

Two things it cannot reach, because Android does not allow it: other apps'
private directories under `/Android/data` and `/Android/obb`, and the system
partitions, which need root.

Access needs the **All files access** permission. From Android 11 that is not a
runtime dialog — the app sends you to a system settings screen, and Google Play
restricts which apps may request it at all, which matters if you ever publish
this rather than sideloading it. On Android 10 and below the ordinary storage
permission covers it. The file manager only appears once the permission is
granted *and* sharing is restarted, since whether to mount it is decided when the
server starts.

Sharing survives leaving the app: a foreground service keeps the server up, with
an ongoing notification carrying a **Stop** action.

### From a terminal

Pass the PIN as a header instead of logging in:

```sh
curl -H 'X-MagicPaste-Pin: 4242' http://192.168.1.42:8123/raw
curl -H 'X-MagicPaste-Pin: 4242' -d 'hello' http://192.168.1.42:8123/raw
```

## HTTP API

Every endpoint except `/health` needs either the session cookie the PIN prompt
sets, or an `X-MagicPaste-Pin` header. Without one, they answer `401`.

| Method     | Path                    | Description                                                |
| ---------- | ----------------------- | ---------------------------------------------------------- |
| `GET`      | `/`                     | The web UI, or the PIN prompt if you have no session        |
| `POST`     | `/api/session`          | Body `{"pin": "..."}` — sets the session cookie             |
| `GET`      | `/api/clipboard`        | `{"text": "...", "revision": N}`                            |
| `GET`      | `/api/clipboard?since=N`| Long-polls: returns once the revision passes `N`, or after 25s |
| `POST/PUT` | `/api/clipboard`        | Body `{"text": "..."}` — replaces the device clipboard      |
| `GET`      | `/raw`                  | Clipboard as `text/plain`                                   |
| `POST/PUT` | `/raw`                  | Request body becomes the clipboard                          |
| `GET`      | `/health`               | `ok` — the one unauthenticated route, so you can probe it   |
| `GET`      | `/files`                | The file manager page                                       |
| `GET`      | `/api/files?path=`      | Directory listing as JSON                                   |
| `GET`      | `/api/files/download?path=` | Streams one file                                        |
| `POST`     | `/api/files/upload?path=` | Multipart upload into that folder                         |
| `POST`     | `/api/files/folder`     | `{"path": "...", "name": "..."}` — creates a folder         |
| `POST`     | `/api/files/rename`     | `{"path": "...", "name": "..."}`                            |
| `POST`     | `/api/files/move`       | `{"path": "...", "destination": "..."}`                     |
| `POST`     | `/api/files/delete`     | `{"path": "..."}` — recursive for folders                   |

`revision` is a counter that increases whenever the clipboard text changes. It is
what makes live updates work without WebSockets: the web page asks for the
clipboard "newer than revision N" and the server holds the request open until
there is one.

Pastes are capped at 1,000,000 characters.

## Two things to know

**Reading the clipboard needs the app on screen.** Since Android 10 the system
only hands the clipboard to an app that holds window focus. MagicPaste caches the
last value it managed to read and serves that while it is in the background, so
anything you copy elsewhere shows up the next time you open the app. Writes have
no such restriction — text sent from a browser lands on the clipboard
immediately, whether or not the app is on screen.

**The PIN is short, so guesses are made expensive.** Four digits is only 10,000
combinations, which a script would exhaust in seconds against an unprotected
endpoint. `PinGate` answers every wrong guess after a delay that doubles up to
five seconds, and serializes attempts so they cannot be run in parallel — which
puts a full sweep on the order of half a day, against a server that is typically
up for minutes. Comparison is constant-time, and the session cookie is a 24-byte
`SecureRandom` token rather than the PIN itself.

What that does *not* protect against: someone on the network who can watch the
traffic. This is plain HTTP, so the PIN, the session cookie and everything served
travel in the clear. Treat it as a lock on the door of a network you already
trust, not as protection on a hostile or public one.

**With the file manager on, that PIN guards your files, not a text snippet.**
Four digits and an unencrypted connection are a reasonable trade for a clipboard;
they are a thin defence for every photo and document on the phone, and delete is
recursive and irreversible. Start sharing only on networks you trust, and stop it
when you are done — stopping also invalidates every session.

**Nothing can address a file outside the shared root.** Two independent defences,
because path traversal is the failure mode this feature would have. `VirtualPath`
refuses any path containing `..` outright rather than resolving it, and refuses
separators inside a name so a rename cannot become a move. `AndroidFileStore`
then resolves the result against the root and compares *canonical* paths, which
catches a symlink pointing outward — something no amount of string checking would
see. Both are covered by tests.

Stopping and restarting sharing invalidates every session, so changing the PIN
locks out browsers that were already connected.

## Layout

```
shared/   Pure-Kotlin, no Android APIs — the server, the clipboard and file
          abstractions, path safety, and the web pages. Ktor CIO and the kotlinx
          libraries are all multiplatform, so this module could move to
          commonMain as-is.
app/      Everything Android: the system clipboard, files on shared storage, the
          foreground service that keeps the server alive, network address
          discovery, and the Compose UI.
```

The seams between them are `ClipboardAccess` and `FileStore` (`shared`),
implemented by `AndroidClipboard` and `AndroidFileStore` (`app`). Porting to
another platform means writing one more implementation of each.

## Building

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew test                      # unit tests
```

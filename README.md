<div align="center">

# 🔮 MagicPaste 📋

**Your phone's clipboard and files, in any browser on the same Wi-Fi.**
</div>

---

Turns an Android device into a small web server on the local Wi-Fi network that
shares its clipboard and its files. Anyone on the same network who knows the PIN
opens `http://magicpaste.local:8123` — or `http://<device-ip>:8123` — in a
browser and can read what the phone copied, push text back onto its clipboard,
and browse, download, upload, rename and delete files on the device.

|                    | |
|--------------------| --- |
| 📋 **Clipboard**   | Live both ways. Copy on the phone and it appears in the browser within a second; send text back and it lands on the clipboard immediately. |
| 📁 **Files**       | Browse shared storage, download, upload by drag and drop, create folders, rename, move, delete. |
| 🏷️ **mDNS**        | Reachable as `magicpaste.local` — or any `.local` name you pick — via mDNS, so nobody has to read an IP address off a phone screen. |
| 🔒 **PIN**         | Four digits, guarded by a throttle that makes guessing impractical. Nothing is served without it. |
| 🔒 **HTTPS**       | Optional encryption with a self-signed certificate, and a fingerprint in the app to check it against. |
| 📵 **No internet** | Nothing leaves your network. No account, no cloud, no external requests — the page is entirely self-contained. |

## Using it

1. Open MagicPaste, choose what to share — **Clipboard**, **Files**, or both —
   optionally switch on **Encrypt traffic (HTTPS)**, and tap **Start sharing**. The port defaults to `8123` and the PIN is a random
   4 digits on first launch; everything is editable while stopped and remembered
   between launches, including the `.local` name the device answers to. On
   devices that allow it (recent Android does), port `80` works too, and then
   the URL needs no port at all: `http://magicpaste.local`.
2. The app lists every address the device can be reached at — the `.local` name
   first, then each IP, Wi-Fi first. Open one on a laptop, tablet or another
   phone on the same network.
3. The browser asks for the PIN, then shows the device clipboard live, with a box
   to send text the other way.
4. For files, tap **Grant file access** in the app once, then follow *Browse files
   on this device* from the web page.

### From a terminal instead of a browser

Pass the PIN as a header instead of logging in (add `-k` when encryption is on,
since the certificate is self-signed):

```sh
curl -H 'X-MagicPaste-Pin: 4242' http://192.168.1.42:8123/raw
curl -H 'X-MagicPaste-Pin: 4242' -d 'hello' http://192.168.1.42:8123/raw
```

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

Access needs the **All files access** permission.

Sharing survives leaving the app: a foreground service keeps the server up, with
an ongoing notification carrying a **Stop** action.

## Important notes

**Reading the clipboard needs the app on screen.** Since Android 10 the system
only hands the clipboard to an app that holds window focus. MagicPaste caches the
last value it managed to read and serves that while it is in the background, so
anything you copy elsewhere shows up the next time you open the app. Writes have
no such restriction — text sent from a browser lands on the clipboard
immediately, whether or not the app is on screen.

**The PIN is short, so guesses are made expensive.** Four digits is only 10,000
combinations, which a script would exhaust in seconds against an unprotected
endpoint. `PinGate` answers every wrong guess after a delay that doubles with
each consecutive failure, without bound, and serializes attempts so they cannot
be run in parallel. A mistyped PIN or two costs under a second; by the
twenty-fifth wrong guess the wait is over an hour, and a full sweep is out of
the question. A correct entry resets the delay. Comparison is constant-time,
and the session cookie is a 24-byte `SecureRandom` token rather than the PIN
itself.

**Over plain HTTP, none of that stops someone who can watch the traffic** — the
PIN, the session cookie and everything served travel in the clear. That is what
the encryption switch is for.

**Encryption is a switch, off by default.** Turn on *Encrypt traffic (HTTPS)* and
the device generates a self-signed certificate covering its current addresses and
serves TLS. Ktor's CIO engine has no TLS support at all — the jar contains no SSL
classes — so an `SSLServerSocket` terminates it and relays bytes to the HTTP
server, which binds to loopback where nothing else can reach it. Relaying bytes
rather than parsing them means long-polling and large transfers behave exactly as
they do over plain HTTP. The session cookie gains the `Secure` flag.

**Typing the bare address still works.** Browsers default to `http://`, and a
plaintext request arriving at a TLS socket is just a failed handshake —
`ERR_EMPTY_RESPONSE`, which tells nobody anything. So the port is a plain socket
that reads one byte first: `0x16` opens every TLS handshake, an ASCII method
opens every HTTP request, and that is enough to tell them apart. Plaintext gets a
`307` to the same path over HTTPS. Temporary rather than permanent on purpose — a
cached `308` would strand browsers on HTTPS after encryption is switched back off.

That defeats **passive** interception completely: someone on your Wi-Fi running a
packet capture sees TLS records, not your PIN and not your files. It does not by
itself defeat an **active** attacker who relays the connection and presents their
own certificate — because the warning they trigger looks exactly like the one a
self-signed certificate always produces.

**The fingerprint is what closes that gap.** The app shows the certificate's
SHA-256: the first and last four bytes large enough to compare at a glance, and
the full digest in the form browsers print it. Open the padlock in the browser,
check they match, and a relay becomes visible. A test asserts that the
fingerprint the app displays is byte-for-byte the one a client actually receives,
because a fingerprint that drifts from reality is worse than none.

Two costs. Browsers warn the first time each device connects — once per browser,
not once per visit, since the exception is remembered — and `curl` needs `-k`. To
be rid of the warning entirely, copy the certificate off the device over USB
rather than downloading it over the network, and install it in the client's trust
store; anchoring trust through a path the network never touched is what makes a
later warning mean something.

The certificate is regenerated whenever the device's address set changes, so its
SAN keeps matching after DHCP moves you. The `.local` name is in the SAN too,
so a trusted certificate stays valid however the page was reached.

**The `.local` name is answered by the app itself.** Android's `NsdManager` can
only advertise service instances, not make a hostname of your choosing
resolvable — so MagicPaste speaks mDNS directly: it joins the multicast group
and answers A-record queries for the configured name with the device's
addresses. On a device with several networks (Wi-Fi plus a hotspot, say) it
answers with the address on the asker's own subnet, so each side gets one it can
actually reach. The name follows DNS label rules — lowercase letters, digits and
inner hyphens, ending in `.local` — and `magicpaste.local` is the default.

Resolution happens on the *visiting* device, so it works wherever mDNS does:
macOS, iOS, Windows 10 and later, Android, and Linux with `avahi-daemon` and
`nss-mdns` installed. On networks that filter multicast — guest Wi-Fi and
AP isolation are the usual culprits — the name silently fails, which is why the
plain IP addresses are always listed alongside it. There is no central registry:
if two devices claim the same name, whichever answers first wins.

**Nothing can address a file outside the shared root.** Two independent defences,
because path traversal is the failure mode this feature would have. `VirtualPath`
refuses any path containing `..` outright rather than resolving it, and refuses
separators inside a name so a rename cannot become a move. `AndroidFileStore`
then resolves the result against the root and compares *canonical* paths, which
catches a symlink pointing outward — something no amount of string checking would
see. Both are covered by tests.

**Stopping and restarting sharing invalidates every session, so changing the PIN
locks out browsers that were already connected.**

## Layout

```
shared/   Pure-Kotlin, no Android APIs — the server, the clipboard and file
          abstractions, path safety, and the web pages. Ktor CIO and the kotlinx
          libraries are all multiplatform, so this module could move to
          commonMain as-is.
app/      Everything Android: the system clipboard, files on shared storage, the
          foreground service that keeps the server alive, network address
          discovery, TLS termination, and the Compose UI.
```

The seams between them are `ClipboardAccess` and `FileStore` (`shared`),
implemented by `AndroidClipboard` and `AndroidFileStore` (`app`). Porting to
another platform means writing one more implementation of each.


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

## Building

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew test                      # unit tests
```

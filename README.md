# MagicPaste

Turns an Android device into a small web server on the local Wi-Fi network that
shares its clipboard. Anyone on the same network opens `http://<device-ip>:8123`
in a browser and can read what the phone copied, or push text back onto the
phone's clipboard.

## Using it

1. Open MagicPaste and tap **Start sharing**. The port defaults to `8123` and is
   editable (remembered between launches).
2. The app lists every address the device can be reached at — usually one, Wi-Fi
   first. Open it on a laptop, tablet or another phone on the same network.
3. The web page shows the device clipboard live, and has a box to send text the
   other way.

Sharing survives leaving the app: a foreground service keeps the server up, with
an ongoing notification carrying a **Stop** action.

### From a terminal

```sh
curl http://192.168.1.42:8123/raw            # read the device clipboard
curl -d 'hello' http://192.168.1.42:8123/raw # set the device clipboard
```

## HTTP API

| Method     | Path                    | Description                                                |
| ---------- | ----------------------- | ---------------------------------------------------------- |
| `GET`      | `/`                     | The web UI                                                  |
| `GET`      | `/api/clipboard`        | `{"text": "...", "revision": N}`                            |
| `GET`      | `/api/clipboard?since=N`| Long-polls: returns once the revision passes `N`, or after 25s |
| `POST/PUT` | `/api/clipboard`        | Body `{"text": "..."}` — replaces the device clipboard      |
| `GET`      | `/raw`                  | Clipboard as `text/plain`                                   |
| `POST/PUT` | `/raw`                  | Request body becomes the clipboard                          |
| `GET`      | `/health`               | `ok`                                                        |

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

**There is no authentication.** Anyone who can reach the port can read and write
the clipboard. That is the intended behaviour on a home or office network you
trust; do not start it on public Wi-Fi.

## Layout

```
shared/   Pure-Kotlin, no Android APIs — the server, the clipboard abstraction,
          and the web page. Ktor CIO and the kotlinx libraries are all
          multiplatform, so this module could move to commonMain as-is.
app/      Everything Android: the system clipboard, the foreground service that
          keeps the server alive, network address discovery, and the Compose UI.
```

The seam between them is `ClipboardAccess` (`shared`), implemented by
`AndroidClipboard` (`app`). Porting to another platform means writing one more
implementation of that interface.

## Building

```sh
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
./gradlew test                      # unit tests
```

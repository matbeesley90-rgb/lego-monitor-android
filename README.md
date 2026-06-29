# LEGO Monitor (Android)

Custom Android client for the LEGO marketplace deal monitor running on a Raspberry Pi.
Receives push messages from the Pi via ntfy WebSocket and renders them as native
Android notifications.

## Phase 1 — scaffold

- Single `MainActivity` showing connection status.
- `WebSocketService` runs as a foreground service holding the ntfy WS open.
- `NotificationRenderer` builds stock `NotificationCompat` notifications from
  incoming ntfy frames (title, body, three action buttons → ACTION_VIEW URLs).
- Runs in parallel with the existing ntfy-Android app for A/B comparison.

## Phase 2 — visual polish (not yet)

- Custom `RemoteViews` with branded fonts (eBay rainbow italic, Vinted teal
  italic, Facebook blue) and inline coloured percentage spans.
- Big-image inline thumbnails without triggering Android's auto Open/Browse
  buttons.

## Phase 3 — direct connection (not yet)

- Tailscale tunnel so the app talks to the Pi directly, bypassing the public
  ntfy server.

## Build

The APK is built by GitHub Actions on every push to `main`. See the latest run
under [Actions](../../actions); the artifact is named `lego-monitor-debug.apk`.

To build locally (requires JDK 17 + Android SDK 34):

```
./gradlew assembleDebug
```

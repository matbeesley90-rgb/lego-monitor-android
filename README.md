# LEGO Monitor — Android client

Native Android client for the LEGO marketplace deal monitor running on a
Raspberry Pi 5. Holds a persistent WebSocket to the Pi's self-hosted ntfy
server and renders incoming deal alerts as branded native notifications.

## Architecture

```
Pi (Flask + scrapers) ──► ntfy (Docker, :8084) ──► WebSocket ──► this app
```

| Component | Role |
|---|---|
| `WebSocketService` | Foreground service; persistent ntfy WS with reconnect/backoff |
| `V4Payload` | Parses the V4 JSON payload (tier, prices, actions, image) |
| `V4NotificationRenderer` | Custom `RemoteViews`: branded platform styling, 2×2 price grid, tier stripe (Yellow = top, Amber = left), inline thumbnail, three action buttons |
| `V4Style` | Colours, brand fonts (eBay rainbow italic, Vinted teal, Facebook blue), tier palette |
| `NotificationRenderer` | Legacy stock-notification fallback for non-V4 frames |
| `MainActivity` | Connection status + service controls |

The V4 notification design is **locked**: title `Platform • XX% [• 🔨 Nm — HH:MM]`,
body = seller title then BN/BU │ EN/EU price grid, tier-coloured stripe,
Listing / Monitor / Catalogue actions. Payload schema: `docs/v4-payload-schema.md`.

## Build & distribution

GitHub Actions builds a debug APK on every push to `main`
(`.github/workflows/build.yml`); grab `lego-monitor-debug.apk` from the run's
artifacts and sideload.

## Configuration

Server endpoint and topic are currently compile-time constants in
`WebSocketService.kt`. Change them there and push to rebuild.

## Roadmap

- Runtime-configurable server/topic (move out of compile-time constants)
- Tailscale direct tunnel to the Pi, bypassing the public port

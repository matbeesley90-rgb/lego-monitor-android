# V4 notification payload schema

The Pi sends this JSON in the ntfy frame's `message` field. The Android app
parses it and renders via custom RemoteViews (brand fonts, coloured spans,
inline image). If JSON parsing fails the app falls back to treating
`message` as plain text (Phase 1 behaviour) so a malformed payload still
shows something useful.

## Schema (v=4)

```json
{
  "v": 4,
  "kind": "deal" | "auction" | "watchlist",
  "brand": "ebay" | "vinted" | "facebook",
  "set_name": "TIE Fighter Pilot Helmet",
  "set_num":  "75274",
  "pct":      56,
  "asking":   63.00,
  "true_cost": 70.35,
  "bl_new":   240,
  "bl_used":  159,
  "eb_new":   300,
  "eb_used":  139,
  "image_url":     "https://.../thumbnail-256.jpg",
  "listing_url":   "https://www.vinted.co.uk/items/12345",
  "monitor_url":   "http://81.96.120.250:5000/#listing=...",
  "catalogue_url": "http://81.96.120.250:5000/#catalogue=sw/sets/75274",

  "_auction_only": {
    "mins_left": 17,
    "end_iso":   "2026-06-29T21:50:00+01:00"
  }
}
```

## Field notes

- `v: 4` — schema marker. Bumped if the shape changes; old app versions silently fall back to plain text.
- `kind` — drives the brand colour + the "🔨 timer" line. `watchlist` may be added later; for now only `deal` and `auction` are sent.
- `brand` — picks the typeface + colour: eBay → sans-bold red/blue/yellow, Vinted → serif italic teal, Facebook → sans blue.
- `pct` — the headline number, integer. App shows green if ≥ 25, yellow if ≥ 12, grey otherwise.
- `asking` / `true_cost` — floats (£). Asking is the seller's listed price; true_cost adds fees + postage (Pi computes via `compute_true_buyer_cost`).
- Market values (`bl_new`, `bl_used`, `eb_new`, `eb_used`) — floats; 0 means "not available", rendered as `—`. Per-cell % computed app-side: `(market - true_cost) / market * 100`.
- `image_url` — already proxied through `/img/proxy?square_crop=1` by the Pi, so the app can download it as-is and call `setImageViewBitmap`.
- `*_url` triple — the three action button destinations (Listing → marketplace, Monitor → web UI deep link, Catalogue → catalogue overlay).
- `_auction_only` — present only when `kind == "auction"`. Carries the timer info for the title's `• 🔨 17m — 21:50` segment.

## Ordering vs. ntfy fields

The Pi still sets the existing ntfy fields for back-compat with stock ntfy-Android:

- `title` — short form (`"Vinted • 56%"` or `"eBay • 31% • 🔨 17m — 21:50"`)
- `actions[]` — same three Listing/Monitor/Catalogue view actions, in case the app falls back to stock rendering
- `icon` — proxied square-crop URL (Phase 1 fallback; the V4 renderer fetches `image_url` directly)
- `priority`, `vibration` — unchanged

The new app **prefers** the V4 JSON in `message` and ignores `title`/`actions`/`icon` when JSON parsing succeeds. Old ntfy-Android shows the JSON as ugly text until it's uninstalled.

## Versioning

`v` is mandatory. The app's parser checks `v == 4` before treating the frame as structured; anything else (or a missing `v`, or invalid JSON) falls back to plain-text rendering. Bumping the schema is a coordinated change: Pi-side flag flip + app-side parser update + app version push.

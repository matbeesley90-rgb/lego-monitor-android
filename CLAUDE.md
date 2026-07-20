# lego-monitor-android — Claude context

Kotlin Android app for Mat's LEGO deal monitor. Receives ntfy pushes (custom V4 notification rendering) AND wraps the Pi-hosted web UI in a WebView so the whole monitor is one installable app. Runs alongside the Python backend on this Pi — **backend context lives in `~/lego-monitor/CLAUDE.md`; read that too.**

## The two halves

| Piece | Where | What |
|---|---|---|
| Backend (scrapers, pricing, notifications, web UI) | `~/lego-monitor/` on this Pi | Python/Flask, systemd `lego-monitor.service`, UI at `http://81.96.120.250:5000` |
| This app | `~/lego-monitor-android/` | WebView home (loads the UI above) + WebSocketService consuming ntfy `ws://81.96.120.250:8084` |

New sessions run on this Pi as user `insideman90` — you already have filesystem access to both repos; no SSH needed.

## GitHub access

- Remote: `https://github.com/matbeesley90-rgb/lego-monitor-android` (this repo has a remote; the backend repo is LOCAL-ONLY, never push it).
- Auth: a PAT is stored in `~/.git-credentials`. **Never print or copy it.** `git push`/`pull` just work.
- `gh` CLI is NOT logged in. Feed it the stored token per-command:
  ```bash
  GH_TOKEN=$(git credential fill <<<$'protocol=https\nhost=github.com\n' | sed -n 's/^password=//p')
  GH_TOKEN="$GH_TOKEN" gh <command>
  ```
- **NEVER push/merge to `main` without Mat's explicit say-so** (auto-mode blocks it anyway). All current work is on branch **`claude/price-in-title`** — main is many commits behind it. When Mat approves, merge via PR or ask him to merge on GitHub.

## Building + delivering an APK

CI is GitHub Actions (`.github/workflows/build.yml`): triggers on push to `main` **and** `workflow_dispatch` (so you can build any branch). Recipe:

```bash
cd ~/lego-monitor-android
GH_TOKEN=$(git credential fill <<<$'protocol=https\nhost=github.com\n' | sed -n 's/^password=//p')
GH_TOKEN="$GH_TOKEN" gh workflow run build.yml --ref claude/price-in-title
sleep 6
rid=$(GH_TOKEN="$GH_TOKEN" gh run list --workflow build.yml --branch claude/price-in-title --limit 1 --json databaseId -q '.[0].databaseId')
# poll: gh run view $rid --json status,conclusion   (takes ~1-2 min)
aid=$(GH_TOKEN="$GH_TOKEN" gh api repos/matbeesley90-rgb/lego-monitor-android/actions/runs/$rid/artifacts -q '.artifacts[0].id')
GH_TOKEN="$GH_TOKEN" gh api repos/matbeesley90-rgb/lego-monitor-android/actions/artifacts/$aid/zip > /tmp/apk.zip
unzip -o /tmp/apk.zip   # -> app-debug.apk
```
Then send the APK to Mat with the SendUserFile tool (display: attach). He installs it directly.

**Signing:** the workflow caches ONE debug keystore (cache key `debug-keystore-v1`) so every build signs identically and installs over the top. If Mat ever gets "App not installed", the cache was lost/changed → he must uninstall once, then it's stable again. Do not remove that workflow step.

## Current state (2026-07-20)

- Latest build: **v14**, commit `085dc95` on `claude/price-in-title` — **awaiting Mat's on-device test.**
- Expected behaviour when tapping a listing: Vinted → Vinted app, eBay → eBay app, Facebook → FB app, **Gumtree → browser flashes then Gumtree app** (via the site's own `intent://` redirect — the Gumtree app has no https deep links). **My O2 must never open.**
- If Mat reports a failure, get it as "[marketplace] → [what opened]" and read `MainActivity.openExternal()` + the memory file `android-marketplace-linking` before changing ANYTHING — the linking design encodes hard-won on-device facts:
  - The **My O2 app** is registered as the verified handler for marketplace domains on Mat's phone — never let the OS resolve a link (no implicit ACTION_VIEW, no queryIntentActivities-based picking).
  - **No CATEGORY_BROWSABLE on explicit-package launches** (it makes matching stricter and broke Vinted once).
  - Handle `intent://` URLs like Chrome (`Intent.parseUri` → launch named package → `S.browser_fallback_url`), never `webView.loadUrl()` a non-http scheme.

## Key files

| File | Role |
|---|---|
| `app/src/main/java/com/lego/monitor/MainActivity.kt` | WebView home + all listing-opening logic (`openExternal`) |
| `app/src/main/java/com/lego/monitor/WebSocketService.kt` | ntfy WebSocket (topics: main + bundles, one socket) |
| `app/src/main/java/com/lego/monitor/V4NotificationRenderer.kt` | Custom RemoteViews notification (price-in-title, fig-value head, bundle bricks+grey head) |
| `app/src/main/res/layout/notification_{collapsed,expanded}.xml` | Notification layouts (fig head + bundle bricks ImageViews) |
| `app/src/main/res/drawable/ic_notification_head.xml` | THE minifig head icon — Mat is very attached to it, never redesign it |
| `app/src/main/res/drawable/ic_notification_bundle.xml` | Stacked-bricks bundle marker |
| `app/src/main/res/xml/network_security_config.xml` | Cleartext exception for 81.96.120.250 only |

## Style / server-tunable notifications

Notification sizes/colours come from the `style` JSON in each V4 payload — config key `notification_style_json` in the backend DB (`~/lego-monitor/lego_listings.db`). Cosmetic tweaks = DB edit on the Pi (5s cache TTL), **no APK rebuild**. Only structural changes need a build.

## Never-do

- ❌ Never print/copy the PAT in `~/.git-credentials`.
- ❌ Never push or merge to `main` without Mat explicitly asking.
- ❌ Never redesign the minifig head icon.
- ❌ Never use implicit link resolution in the app (O2 hijack — see above).
- ❌ Never remove the CI keystore-cache step (breaks over-the-top installs).
- ❌ Backend repo (`~/lego-monitor`) has NO remote by design — never add one or push it.

## Open items (as of session close)

1. **v14 test pending** — Mat to confirm where the four marketplaces open.
2. Branch `claude/price-in-title` unmerged — needs Mat's go-ahead, then the push-to-main build produces the "official" APK.
3. Backend extras Mat hasn't decided on: eBay price auto-refresh (sw_sets prices freeze after one pass), false-positive content check in the daily notif review (`~/lego-monitor/daily_notif_review.py`, Pi cron at noon).

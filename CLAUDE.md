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
~/.lego-monitor-signing/resign.sh app-debug.apk   # MANDATORY — see Signing
```
Then send the APK to Mat with the SendUserFile tool (display: attach). He installs it directly.

**Signing (fixed 2026-07-20 after v16):** CI signs every build with a FRESH throwaway key — the workflow's keystore-cache step restores `~/.android/debug.keystore` but AGP on the runner provably doesn't use it (v14/v15/v16 artifacts all had different certs; the "stable key" belief was wrong, over-the-top installs only ever worked after an uninstall). The real fix is Pi-side: **always run `~/.lego-monitor-signing/resign.sh <apk>` on the downloaded artifact before sending it to Mat.** It re-signs with the permanent keystore at `~/.lego-monitor-signing/debug.keystore` (PKCS12, pass `android`, alias `androiddebugkey`, cert SHA-256 `55974c6d…`) using a local JRE + apksigner in the same dir. That keystore is the ONLY key that matters — never delete `~/.lego-monitor-signing/`, never commit it (public repo). Skipping the resign step = Mat gets "App not installed".

## Current state (2026-07-20)

- **PENDING BUILD (2026-07-23):** `claude/price-in-title` has an unbuilt commit — bundle notification fig line reworked. The footer TextView is now wrapped in `notif_footer_row` (LinearLayout) with a leading `notif_footer_head` ImageView (the minifig head) tinted to the top fig's value band from the V4 `icon_color`, replacing the old orange diamond. Title-row grey head dropped for bundles (bricks stay). Backend already sends the new `icon_color` + reworded `bundle_line` (`£TOTAL • Nfigs • x̄£AVG • +PROFIT% • £PROFIT`). Auto-mode blocked the gh build trigger — Mat needs to run the documented `gh workflow run build.yml --ref claude/price-in-title` recipe, then resign+deliver. Only the head VISUAL is waiting on this; the text is live server-side.
- Latest build: **v16**, commit `c42a045` on `claude/price-in-title` — v15 test result: Gumtree/eBay/FB → native apps ✓, Vinted → browser ✗. v16 fixes Vinted: the UK app is **`fr.vinted`** (its manifest lists `www.vinted.co.uk`); `com.vinted` is the separate US-only app and was never installed. **Awaiting Mat's v16 Vinted re-test.** Do not change the linking code without a new failure report.
- Behaviour when tapping a listing: Vinted → Vinted app, eBay → eBay app, Facebook → FB app, Gumtree → Gumtree app. **My O2 must never open.**
- v15 Gumtree fact (from decoding the Gumtree APK's own manifest, July-2026 version): `com.gumtree.android`'s MainActivity filters `https://www.gumtree.com` with `pathPattern /p/.*` — the monitor's listing URLs match. It's absent from the OS resolver only because App Links *verification* fails on Mat's phone; an explicit `setPackage` launch needs no verification. The old "direct https launch dead-ends on an error page" note was wrong for current listing URLs (likely an expired ad or older app version) — v15 confirmed the direct launch works on-device.
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

1. Branch `claude/price-in-title` unmerged — needs Mat's go-ahead, then the push-to-main build produces the "official" APK. v15 is confirmed good, so it's ready to merge whenever he says so.
3. Backend extras Mat hasn't decided on: eBay price auto-refresh (sw_sets prices freeze after one pass), false-positive content check in the daily notif review (`~/lego-monitor/daily_notif_review.py`, Pi cron at noon).

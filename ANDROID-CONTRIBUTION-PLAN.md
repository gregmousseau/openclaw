# OpenClaw Android Contribution Plan

*Created: 2026-02-17 by Greg Mousseau*

---

## Project Context

- **Repo:** `github.com/openclaw/openclaw` (203K ⭐, 36.7K forks)
- **Android app:** `apps/android/` — Kotlin + Jetpack Compose, minSdk 31, targetSdk 36
- **Latest version in repo:** `2026.2.16` (versionCode 202602160)
- **Design system:** Material 3 with `dynamicDarkColorScheme` / `dynamicLightColorScheme` (Material You)
- **No dedicated Android maintainer listed** in CONTRIBUTING.md — opportunity!
- **iOS maintainers:** @obviyus (Ayaan Zaidi), @mbelinky (Mariano Belinky)

## Current State

### Architecture
- Main screen is a **WebView canvas** with overlay buttons (Chat, Talk, Settings)
- Chat and Settings are **ModalBottomSheets**
- Connection via **foreground service** (WebSocket to Gateway)
- Discovery via **NSD/mDNS** (`_openclaw-gw._tcp`)
- Theme: Material 3 dynamic color (follows system wallpaper)

### iOS Comparison (more polished)
- iOS uses **TabView** with 3 tabs: Screen, Voice, Settings
- Has proper **disconnect confirmation dialog** 
- Has **VoiceWake toast** notifications
- Structured with separate tab views vs Android's overlay-everything approach
- iOS has a `StatusPill` with tap → gateway actions (disconnect option!)

### Web UI
- No separate web-ui app directory — the "Control UI" is embedded in the gateway (Lit components)
- Recent refresh of design system (typography, colors, spacing) in v2026.1.24

## Open Android PRs (as of Feb 17)

| PR | Title | Status |
|----|-------|--------|
| #18832 | `harden: Enforce TLSv1.2 and document security model in GatewayTls` | Open, `app: android` label |
| #16274 | `feat(voice): Fix persistent speech errors, silent playback, and feedback` | Open, `app: android`, 3/14 tasks done |
| #15953 | `fix: Android/Termux node fails to discover commands` | Draft |
| #15951 | `fix: Android production build permits cleartext traffic globally` | Open, `app: android`, trusted-contributor |

## Bugs We've Hit (Prioritized)

### 🔴 P0 — Blocks basic usage

1. **JSON "Unauthorized" error displayed on home screen**
   - Canvas WebView showing raw JSON error response
   - Persists even after auth is resolved
   - Likely: WebView loads canvas URL before auth token is ready, gets 401, never retries
   - Fix: Intercept HTTP errors in WebViewClient, show friendly state, auto-retry on auth

2. **No way to stop reconnection / disconnect from app**
   - When gateway goes offline (e.g., computer sleeps), app endlessly reconnects
   - No disconnect button accessible — have to force-stop
   - iOS has this! (confirmationDialog with "Disconnect" option on StatusPill tap)
   - Fix: Port iOS pattern — tap StatusPill → disconnect option

3. **Voice button cycling on/off, audio interference**
   - Talk mode toggle is buggy — keeps cycling
   - Pauses system audio (music, podcasts)
   - Binging sound persists after closing app
   - Related open PR: #16274 (voice fixes)
   - Fix: Review PR #16274, may need additional audio focus handling

### 🟡 P1 — Poor UX

4. **mDNS/NSD auto-discovery fails (Tailscale and similar)**
   - Manual gateway entry worked, but discovery didn't
   - Common for Tailscale/VPN users — mDNS doesn't cross network boundaries
   - Fix: Better fallback UX, manual entry more prominent, detect Tailscale and suggest manual

5. **Chat: No collapsible thinking/code/JSON blocks**
   - Have to scroll through 1000s of lines of thinking, code, JSON tool output
   - Need: Collapsible sections for thinking, code blocks, JSON
   - Need: Auto-scroll to latest actual response
   - Need: Visual distinction between assistant prose vs tool/thinking output

6. **Chat: Input doesn't auto-capitalize first letter**
   - Android keyboard defaults to lowercase
   - Fix: Set `KeyboardCapitalization.Sentences` on the TextField

### 🟢 P2 — Polish

7. **Home screen is basically empty** (just canvas WebView + overlay buttons)
   - No status dashboard, no quick actions, no recent conversations
   - iOS at least has tab structure giving better navigation

8. **No connection status details**
   - Just "Connected" pill — no gateway name, latency, session info
   - Could show more context like iOS does

## Proposed PR Sequence

### PR 1: Auto-capitalize chat input (quick win, intro PR)
- Tiny change, gets feet wet with the contribution process
- File: `ChatComposer.kt`
- Add `keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)`

### PR 2: Add disconnect button (P0, high value)
- Port iOS pattern: StatusPill tap → confirmation dialog → disconnect
- References: iOS `RootTabs.swift` confirmationDialog pattern
- Files: `RootScreen.kt`, `StatusPill.kt`, `MainViewModel.kt`

### PR 3: Fix Unauthorized JSON display (P0)
- Intercept 401 in WebViewClient, show retry/reconnect UI instead of raw JSON
- Files: `RootScreen.kt` (CanvasView), potentially new `CanvasErrorState.kt`

### PR 4: Collapsible thinking/code/JSON in chat (P1, flagship PR)
- Parse message content for thinking blocks, code fences, JSON
- Render as expandable/collapsible Material 3 cards
- Auto-scroll to bottom on new assistant message
- Files: `ChatMessageViews.kt`, `ChatMarkdown.kt`, new `CollapsibleContent.kt`

### PR 5: Voice mode stability (P0, build on #16274)
- Review and potentially extend PR #16274
- Proper audio focus management
- Clean shutdown of audio resources

## Design Guidelines

- **Material 3** (Material You) — already in use, follow existing patterns
- **Dynamic color** — respect system wallpaper-derived theme
- Use existing `overlayContainerColor()` / `overlayIconColor()` helpers
- Follow iOS as reference for UX patterns (it's more mature)
- Keep PRs focused — one fix per PR per CONTRIBUTING.md

## Dev Environment

- **Java:** OpenJDK 17 ✅
- **Android SDK:** ~/Android/sdk (API 35+36, build-tools 35.0.0) ✅
- **Repo cloned:** ~/pro/openclaw ✅
- **Build:** `ANDROID_HOME=~/Android/sdk ./gradlew :app:assembleDebug` (in progress)
- **Testing:** Can't run on emulator from WSL easily — build APK, sideload to OnePlus 12

## Key Files

```
apps/android/
├── app/src/main/java/ai/openclaw/android/
│   ├── ui/
│   │   ├── RootScreen.kt          — Main screen (WebView + overlays)
│   │   ├── OpenClawTheme.kt       — Material 3 theme
│   │   ├── StatusPill.kt          — Connection status indicator
│   │   ├── SettingsSheet.kt       — Settings bottom sheet
│   │   ├── TalkOrbOverlay.kt      — Voice mode UI
│   │   ├── CameraHudOverlay.kt    — Camera UI
│   │   └── chat/
│   │       ├── ChatSheetContent.kt — Chat bottom sheet
│   │       ├── ChatComposer.kt     — Message input
│   │       ├── ChatMessageViews.kt — Message rendering
│   │       └── ChatMarkdown.kt     — Markdown rendering
│   ├── MainViewModel.kt           — Main view model
│   ├── NodeApp.kt                 — Application class
│   └── node/
│       └── ConnectionManager*.kt  — Gateway connection
├── app/build.gradle.kts
└── README.md
```

## References

- CONTRIBUTING.md: Bugs & small fixes → Open a PR. AI/vibe-coded PRs welcome.
- iOS app: `apps/ios/` — more polished, use as UX reference
- Shared code: `apps/shared/OpenClawKit/` — shared transport/types

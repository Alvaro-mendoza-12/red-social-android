# Project Health 2026-05-14

## Scope

Reviewed the repository structure for scalability and cleanliness across Android, web, SQL, Node signaling, docs and local artifacts.

## Active Architecture

- Android app: `app/`, Kotlin/Jetpack Compose/Hilt/Room/Supabase.
- Web client: `Conectate-web/`, Vite/React.
- Signaling backend: `signaling-server/`, Node + Socket.io.
- Database path: `sql/release-manifest.txt` plus categorized SQL folders.
- Automation: `tools/quality_gate.ps1`, security audit, runtime audit and performance budget scripts.

## Size Signals

- Android active Kotlin files: 451 under `app/src/main/java`.
- Android unit-test Kotlin files: 24 under `app/src/test/java`.
- Web TS/TSX files: 53 under `Conectate-web/src`.
- SQL files: 217 under `sql`.

## Main Scalability Risks

- Large Android repositories and screens make feature changes risky. Biggest hotspots include `MessageRepository.kt`, `VideoRepository.kt`, `UserRepository.kt`, `ChatScreen.kt`, `CommunityRepository.kt`, `GroupRepository.kt`, `VoiceRoomRepository.kt` and `WebRTCManager.kt`.
- `Conectate-web/src/index.css` and message pages are very large, so styling and messaging UI should be split into feature CSS/modules and smaller components over time.
- SQL is much healthier than before because it has a manifest, but legacy scripts must stay out of automated deploys.
- Root-level historical docs, repair scripts, build logs, snapshots and concept code were competing visually with the active product.

## Cleanup Applied

- Root legacy documentation moved to `docs/archive/root-legacy/`.
- Build logs moved to `docs/archive/build-logs/`.
- Root SQL module files moved to `sql/archive/root-modules/`.
- Root repair scripts moved to `tools/legacy/root-fix-scripts/`.
- Concept-only top-level Kotlin systems moved to `docs/archive/experimental-concepts/`.
- Versioned backup/quarantine material moved under `docs/archive/`.
- Accidental `.FullName` artifact removed.
- Added repo hygiene guardrails in `.gitignore`, `AGENTS.md` and `tools/repo_hygiene_check.ps1`.

## Next Refactor Targets

1. Split `MessageRepository.kt` by remote store, local cache, realtime sync, media and policy concerns.
2. Split `ChatScreen.kt` into composer, message list, attachment/media, header and side-effect handlers.
3. Move web global CSS toward feature-level styles to reduce regression risk.
4. Keep every new SQL file classified and explicitly add only deployable scripts to `sql/release-manifest.txt`.

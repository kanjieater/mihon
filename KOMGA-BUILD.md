# Personal Komga page-sync build

Based on [Mihon PR #3481](https://github.com/mihonapp/mihon/pull/3481), head `a968d15c65add7b4b12a9b561782ae0439aa2684`, with both pgosar patches from the July 19 comments.

## Behavior

- Library pull-to-refresh syncs trackers even if the library update job cannot start.
- Tracker refresh runs on the IO dispatcher.
- Opening/resuming a series screen pulls page trackers at most once per minute, and continues polling while the screen is resumed.
- Manual refresh bypasses the one-minute throttle.
- Opening a chapter or pressing Resume waits for the pending/stale page-tracker pull. A failed pull shows an error and does not open the reader; retry after resolving the connection problem.
- Only successful pulls are cached. Cancellation is propagated.
- Page-turn pushes are unchanged and are NOT minute-throttled. Each push captures the selected page index rather than reading the later mutable index.
- Page-turn pushes require automatic tracking updates enabled, a logged-in tracker associated with the series, and incognito mode off. Network failures are logged; this PR does not provide guaranteed offline delivery of page pushes.

For Komga as the source of truth, choose **Use tracker progress** in the sync-strategy preference.

## Install on Samsung S25 Ultra

Use the signed **arm64-v8a** APK from this fork's Releases. Package ID is `app.mihon.debug` (the preview variant), so it installs alongside official Mihon rather than requiring its private signing key. Export a backup from the existing app, restore it here, and configure Komga and extension repositories as needed. Do not uninstall the original app before verifying the restore.

Telemetry and the upstream auto-updater are disabled for this custom build. Updates must use APKs from this fork, signed with the same personal key.

## Rebuild

Push to `komga-page-sync`, or manually run **Komga Sync APK (S25 Ultra)** in GitHub Actions. The workflow checks formatting, runs unit tests and SQLDelight migration verification, then builds `assemblePreview` and uploads the ARM64 APK.

Signing material is stored in GitHub Actions secrets `KOMGA_SIGNING_KEY` and `KOMGA_KEY_PASSWORD`, not in Git. A private local backup is retained outside the checkout in `~/.local/share/mihon-build/`.

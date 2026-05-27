# Archive

This directory keeps historical material that is useful for recovery, comparison or audit, but is not part of the active build or deploy path.

## Contents

- `root-legacy/`: old root-level reports, guides and implementation notes.
- `build-logs/`: tracked build logs kept only for historical comparison.
- `experimental-concepts/`: aspirational Kotlin concept systems that are not wired into Gradle.
- `backup-snapshots/`: versioned snapshots previously stored under root `backup/`.
- `app-backup-before-restore/`: old Android app snapshot retained for manual recovery.
- `recovery-quarantine/`: tracked quarantine material previously stored under root `tmp/`.

## Rule

Do not import or update archived code in place. If something is recovered, copy the needed piece into the active tree, review it manually and verify with the normal build/tests.

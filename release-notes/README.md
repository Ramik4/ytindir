# Release notes

Every release has its notes here as `vX.Y.Z.md`, written **before** the release is merged into
`main`: the release workflow uses this file as the GitHub release's notes (without it, the merge
commit's message is used). Changes to this folder don't build a new release, so a file can also be
fixed afterwards here and pasted into the release on GitHub.

Format (see the latest file for an example):

- English first, then Turkish in `<!-- notes:tr ... -->` and Russian in `<!-- notes:ru ... -->`
- Two sections only:
  - `## ✨ New Features` / `## ✨ Yeni Özellikler` / `## ✨ Новые возможности`
  - `## 🔧 Changes and improvements` / `## 🔧 Değişiklikler ve iyileştirmeler` / `## 🔧 Изменения и улучшения`
- Features and changes only, as users see them: no PR numbers or links (#25), no commit or
  technical details, no "Generated with Claude Code" or similar lines, and no closing
  "Bug fixes and other improvements" line
- Menu and button names exactly as the app shows them in each language

The 4PDA post is made from the Russian notes: see `docs/4pda-post.md`.

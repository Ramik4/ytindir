# YTindir

- Merging into `main` publishes a public release. Test builds: run the "YTindir APK" workflow
  (workflow_dispatch) on the branch, which makes a draft "beta" release.
- Before a release is merged: bump the version in `app/build.gradle` and write
  `release-notes/vX.Y.Z.md` (format in `release-notes/README.md`), then the 4PDA post
  (`docs/4pda-post.md`).
- No "Generated with Claude Code" or similar lines in PR descriptions, release notes or merge
  commit messages.

# Android_Development — Nova

`nova-pip-build` is the canonical development branch for the current custom Nova build.

The finalized modified source is checked in directly under `nova/`:

- `nova/Video`
- `nova/MediaLib`
- `nova/native/avos`

Current feature baseline: handheld PiP, diagnostics/export, local subtitle-file loading, and HDR-to-SDR conversion.

Development follows [AGENTS.md](./AGENTS.md): develop and validate locally first, review the final diff, commit coherent source changes, then use GitHub Actions only for authoritative Android/NDK validation and APK generation.

The build workflow does not inject feature source or apply feature patches. It validates the checked-in source trees, retrieves unchanged upstream dependencies, builds Nova, and uploads the APK artifact.

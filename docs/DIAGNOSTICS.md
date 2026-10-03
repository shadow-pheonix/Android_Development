# Mandatory Diagnostics Standard

Every Android app developed in this repository must provide a diagnostics export suitable for debugging device-only problems without requiring direct ADB/logcat access.

## Required user flow

Each app must provide a user-accessible **Export diagnostics** action in builds used for development/testing.

When triggered, it must create a ZIP file in:

```text
Internal storage/AppDebug/<AppName>/
```

On standard Android shared storage this normally corresponds to:

```text
/storage/emulated/0/AppDebug/<AppName>/
```

Use Android storage APIs that are compatible with the target Android version and scoped storage. Do not request broad all-files access merely to satisfy this requirement when MediaStore, SAF, or another appropriate app-compatible mechanism can save the export.

Recommended filename:

```text
<AppName>-diagnostics-YYYYMMDD-HHmmss.zip
```

## Minimum ZIP contents

The bundle must contain enough context to debug crashes and behavioral problems. At minimum include:

```text
app.log
crash.txt                  # or crashes/ when multiple crash records are retained
device-info.txt
app-info.txt
build-info.txt
```

Where relevant, apps may also include sanitized structured state such as:

```text
diagnostics.json
preferences.json
playback-state.json
network-errors.log
```

Only include additional state when it is useful and safe to export.

## Logging requirements

Application logging must be persistent enough to survive navigation and ordinary app restarts so a problem can be reproduced before exporting.

Logs should capture, where relevant:

- timestamp;
- severity/level;
- thread or coroutine context when useful;
- major lifecycle events;
- important feature actions/state transitions;
- handled exceptions and errors;
- network/API failures without secrets;
- app-specific debugging events needed to understand failures.

Logs must be **bounded and rotating**. A reasonable default is a combined retained log budget of approximately **10 MiB or less per app**, unless the app has a justified different requirement.

## Crash capture

Apps should retain the previous uncaught crash/exception information when technically possible so the user can reopen the app and export the diagnostics bundle after a crash.

The crash record should include:

- timestamp;
- exception type/message;
- stack trace;
- app version/build identity;
- relevant process/thread information when available.

When practical, the app may surface a message such as **Previous session crashed — Export diagnostics** after restart.

## Device, app, and build identity

The bundle must identify the environment sufficiently to connect a report to the exact build. Include as appropriate:

- app name and application ID;
- version name and version code;
- build type/flavor;
- source commit SHA when available;
- CI workflow/run identity when available;
- Android version/API level;
- manufacturer/model/device;
- ABI(s);
- relevant display/device capabilities when useful to that app.

## Privacy and security

Diagnostics must not intentionally export:

- passwords;
- auth/session tokens;
- API keys;
- private/signing keys;
- payment credentials;
- unnecessary private messages, files, media, or user content.

Sanitize URLs, headers, request bodies, preferences, and state before export when they may contain credentials or sensitive data.

## Development requirement

For a new app, diagnostics export is part of the initial baseline and must exist before the first normal APK handoff.

For an existing app that lacks this capability, add the diagnostics baseline during active development unless the user explicitly asks to defer it.

The implementation should be verified locally as far as possible, and the Android build must be validated through the app's normal GitHub Actions workflow before APK delivery.

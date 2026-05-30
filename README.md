# TermPort

TermPort is an Android terminal frontend focused on reliable console input, persistent sessions, and switching between local Termux sessions and external terminal backends.

## Current Backends

- Termux sessions via `com.termux.RUN_COMMAND` and a bundled Python bridge.
- Skydnir Engine API sessions via `127.0.0.1:2375`, using Docker-compatible exec attach when a running container is available.

## Android Package

`io.github.ryo100794.termport`

## Build

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

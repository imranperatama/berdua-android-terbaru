# Berdua Android — source starter

Android starter project for a minimal dark two-device chat prototype.

## Build

The project is built by the separate GitHub Actions workflow `build-apk-v2.yml`. The source ZIP intentionally does **not** contain `.github/workflows`; upload the two workflow YAML files separately so older workflows are not reintroduced by extraction.

## Current scope and limitations

- Native Android UI and an embedded local HTTP server starter.
- Private app storage for local message data.
- Basic pair-code and one-remote-device checks.
- Photo/video picker, voice recording, and last-known-location sharing starter flows.

This is not yet a production-secure messenger. The source does not automatically create an ngrok tunnel, implement end-to-end encryption, or guarantee push notifications while the app is closed. The local server is only reachable outside the device if a separate, correctly secured network tunnel is configured. Test on devices and review security before sharing personal data.

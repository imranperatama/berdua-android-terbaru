# Berdua — setup from ZIP on GitHub mobile

## What is in the ZIP

The archive contains the complete Android project under `berdua-android/`, including the `app/` folder, Gradle Kotlin build files, resources, Kotlin source, and both GitHub Actions workflows.

## Steps

1. Upload `berdua-source.zip` to the **root** of the GitHub repository and commit it.
2. Open **Actions → Prepare Berdua source from ZIP → Run workflow**.
3. Wait until the preparation workflow succeeds. It copies the project files to the repository root and removes the ZIP from the repository.
4. Open **Actions → Build Berdua APK → Run workflow**.
5. After a successful build, open the run and download the `berdua-debug-apk` artifact.

The build workflow is deliberately not triggered when only the ZIP or workflow files are uploaded. Run the prepare workflow first, then start the build manually.

## Limits of this starter

- The APK does not start an ngrok tunnel automatically. A tunnel must be configured separately to connect over the internet.
- Reliable notifications after Android closes or suspends the app are not implemented.
- Scheduled access controls and end-to-end encryption are not implemented. Treat this as an early test project, not for highly sensitive information.
- Microphone and location prompts are shown normally; the app does not hide or bypass Android permissions.
- The workflow creates a debug APK for testing, not a production-signed release.

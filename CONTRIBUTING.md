# Contributing to AsyncAPI Parser KMP

## Building Locally

### Prerequisites

- JDK 17 or higher
- Node.js 18 or higher
- `json-schema-ref-parser-kmp` >= `0.9.22`.

### Build Commands

```bash
# Clean and build everything
./gradlew clean build

# Run JVM tests
./gradlew jvmTest

# Run JS tests
./gradlew jsTest

# Run tests with coverage reports
./gradlew build koverHtmlReport koverLog

# Print coverage in the console
./gradlew koverPrintCoverage

# Publish to Maven Local
./gradlew clean publishToMavenLocal
```

## Release Process

See [RELEASING.md](RELEASING.md) and [docs/release-security.md](docs/release-security.md)
for the full flow. In short: add a `release-notes/release-notes.v<version>.md`
file to `main`, then trigger the **Release from Notes** workflow from GitHub
Actions with the release version (and, optionally, the next development
version and whether to publish to npm). It prepares the version bump, tags the
release, builds credential-free, and — after you approve the protected
`maven-central-upload` environment — signs and uploads the deployment to Maven
Central as USER_MANAGED, then creates the GitHub Release. A human still has to
log into [central.sonatype.com](https://central.sonatype.com) and click
**Publish** to make it live.

### Snapshot Releases

Snapshots are automatically built and published on pushes to `develop` and `next` through the **Build and Publish Snapshots** workflow.

### Main Branch Verification

Pushes to `main` trigger the **Verify Main and Publish Coverage** workflow, which builds, tests, generates Kover coverage reports, and publishes coverage badges to the `badges` branch.

## Required Secrets

The following GitHub secrets must be configured for publishing:

- `CENTRAL_USERNAME` - Maven Central username
- `CENTRAL_TOKEN` - Maven Central token
- `SIGN_KEY` - GPG signing key
- `SIGN_KEY_PASS` - GPG signing key password

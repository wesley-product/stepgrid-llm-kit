# Releasing

One maintainer, manual releases. This is the checklist; nothing here is automated yet.

## Before tagging

- [ ] **JDK 21.** LiteRT-LM ships Java 21 class files, so anything older fails to load its types.
- [ ] `./gradlew :resume:test :device-tier:testDebugUnitTest :engine:testDebugUnitTest` — green
- [ ] CI green on `main` for the commit being released
- [ ] A real app on a real device has exercised the engine end to end: tier → download → `useModel`
      → `sendStream`, with a streamed reply actually arriving. Unit tests cannot cover the native
      boundary; **do not release a version whose engine has never run on a device.**
- [ ] `CHANGELOG.md` has an entry for this version, and the `[x.y.z]` link at the bottom points at
      the tag that is about to exist
- [ ] Public API reviewed for anything that should not be frozen — `explicitApi()` means every
      `public` is a promise

## Cutting the release

1. **Version.** `gradle.properties` → `VERSION_NAME=x.y.z` (drop `-SNAPSHOT`). Nothing else
   carries a version; module POMs read this one.
2. **Credentials** in `~/.gradle/gradle.properties` — never in this repository:
   ```properties
   mavenCentralUsername=<Central Portal user token name>
   mavenCentralPassword=<Central Portal user token password>
   signingInMemoryKey=<ASCII-armored private key, newlines as \n>
   signingInMemoryKeyId=<last 8 chars of the key id>
   signingInMemoryKeyPassword=<passphrase>
   ```
   `SONATYPE_HOST=CENTRAL_PORTAL` and `RELEASE_SIGNING_ENABLED=true` are already set in this
   repository's `gradle.properties`, so no build change is needed.
3. **Dry run.** `./gradlew publishToMavenLocal` and check `~/.m2/repository/io/github/wesley-product/`:
   each of `llm-kit`, `engine`, `device-tier`, `resume` must have the artifact (`.aar`/`.jar`),
   `-sources.jar`, `-javadoc.jar`, `.pom` and `.module`. The `llm-kit` POM must list the other
   three as `compile` dependencies.
4. **Publish.** `./gradlew publishAllPublicationsToMavenCentralRepository`, then release the
   deployment in the [Central Portal](https://central.sonatype.com/publishing/deployments).
   Validation failures are shown there, not in Gradle output.

   **This step does not work on Windows.** The plugin stages the bundle under a `file://C:\...`
   URI and then fails with `Cannot convert URI '...' to a file` — same on plugin 0.30.0 and 0.33.0.
   Until that is fixed upstream, build the bundle from the local repository by hand and post it to
   the Portal's REST API:

   ```bash
   ./gradlew publishToMavenLocal          # step 3 already did this
   ```
   Then zip `~/.m2/repository/io/github/wesley-product/**` for this version — keeping the Maven
   directory layout, and including the `.asc` signature and `.md5`/`.sha1` checksums next to every
   file — and upload it:
   ```
   POST https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED
        Authorization: Bearer <base64 of "user-token-name:user-token-password">
        multipart/form-data, field name "bundle"
   ```
   The response body is the deployment id. Poll
   `POST /api/v1/publisher/status?id=<id>` until the state is `VALIDATED` (or `FAILED`, which lists
   the reasons), and `DELETE /api/v1/publisher/deployment/<id>` throws an unreleased one away.
   `USER_MANAGED` means nothing is public until the Portal's **Publish** button is pressed —
   which is the one irreversible step in this whole document.
5. **Tag.** `git tag vx.y.z && git push origin vx.y.z`, and create a GitHub release pointing at the
   changelog entry — the `CHANGELOG.md` link expects it to exist.
6. **Back to snapshot.** `VERSION_NAME=x.y.(z+1)-SNAPSHOT`, commit.

## After 0.1.0 specifically

- [ ] Remove the **Status** note from `README.md` and `README.ko.md` — it says the release is not
      out yet, and it stops being true the moment it is
- [ ] In the consuming app (StepGrid): drop `-SNAPSHOT` from the version catalog and **delete the
      `includeBuild` block** in `settings.gradle.kts`. The dependency declarations stay as they are;
      that is the whole point of using release coordinates locally
- [ ] Rebuild the app against the published artifact and run it on a device once — a composite
      build and a resolved artifact are not the same thing

## Namespace

`io.github.wesley-product` is verified through the GitHub organisation of the same name. If the
organisation is ever renamed, the namespace has to be re-verified and the group changes — which is
a breaking change for every consumer, so it does not happen after 1.0.

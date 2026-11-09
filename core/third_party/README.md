# third_party/maven

A Maven repository for libraries that are not published anywhere yet. Gradle reads it for the `com.excor` group only
(see `settings.gradle.kts`).

| Artifact | Built from | Licence |
|---|---|---|
| `com.excor:eXcor-android:0.2.0` | https://github.com/bxanedot/eXcor at `bbeee33` | Apache-2.0 |

To update eXcor, from an eXcor checkout with `local.properties` pointing at the Android SDK (JDK 27):

```bash
./gradlew -Dmaven.repo.local=<nuvx>/third_party/maven publishAndroidPublicationToMavenLocal
```

Then delete the new `-sources.jar` and `maven-metadata-local.xml`, bump the version in `app/` and `tv/`
`build.gradle.kts`, and update the commit above.

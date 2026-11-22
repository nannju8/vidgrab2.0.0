# VidGrab — Android (Kotlin 2.0.20 · Jetpack Compose Material 3)

Navigateur + détecteur de vidéos + gestionnaire de téléchargements haute vitesse.

## Versions
| Élément | Version |
|---|---|
| Kotlin (K2) + plugin `org.jetbrains.kotlin.plugin.compose` | 2.0.20 |
| Android Gradle Plugin | 8.6.1 |
| Gradle | 8.9 |
| Java | 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 29 |
| Compose BOM | 2024.09.03 |
| Media3 (exoplayer, ui, transformer) | 1.4.1 |
| WorkManager | 2.9.1 |
| OkHttp | 4.12.0 |

## Compiler et lancer
1. Android Studio Ladybug (2024.2.1) ou plus récent → **File ▸ Open** → dossier `android/`.
2. Laisser la synchronisation Gradle se terminer (JDK 17 = « Embedded JDK »).
   Si `gradlew` est absent : `gradle wrapper --gradle-version 8.9` (ou laisser Android Studio utiliser la distribution déclarée dans `gradle/wrapper/gradle-wrapper.properties`).
3. Brancher un appareil / lancer un émulateur (API 29+) → **Run ▸ app**.
4. Accepter la permission de notifications (Android 13+).

APK debug : `./gradlew assembleDebug` → `app/build/outputs/apk/debug/`.

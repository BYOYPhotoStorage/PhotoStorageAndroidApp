# PhotoStorage - Android Project Context

## Technology Stack
- **Language:** Kotlin (primary)
- **Build System:** Gradle with Android Gradle Plugin (AGP 9.1.1)
- **Kotlin Version:** 2.2.10
- **Min SDK:** 33, Target SDK: 34, Compile SDK: 34
- **Java Version:** 17 (sourceCompatibility and targetCompatibility)
- **Architecture:** Traditional Activities + ViewModels (no single Activity architecture)
- **UI:** XML layouts with ViewBinding (NOT Jetpack Compose)
- **Async:** Kotlin Coroutines
- **Image Loading:** Glide
- **Cloud Storage:** AWS S3 SDK (aws.sdk.kotlin:s3)
- **HTTP Client:** AWS Smithy OkHttp engine
- **Security:** AndroidX Security Crypto (EncryptedSharedPreferences)
- **Testing:** JUnit 4
- **Package:** com.hriyaan.photostorage

## Build Commands
```bash
# Compile (debug)
./gradlew build --console=plain --quiet

# Unit tests only
./gradlew test --console=plain --quiet

# Lint
./gradlew lint --console=plain --quiet
```

## Code Conventions
- **Kotlin style:** Standard Kotlin conventions, 4-space indentation
- **Null safety:** Prefer null-safe operations, avoid `!!` unless justified
- **Coroutines:** Use lifecycleScope in Activities for UI-related async work
- **Error handling:** Use try/catch for external calls (S3, database); return nullable types or sealed classes for domain errors
- **ViewBinding:** Access views via binding in Activities after `setContentView(binding.root)`
- **Tests:** Every data class with logic should have unit tests

## Project Structure
```
app/src/main/java/com/hriyaan/photostorage/
  data/          # Data models, database access, preferences
    B2Credentials.kt      # Cloud storage credentials
    MediaStorePhoto.kt    # Photo metadata from MediaStore
    MediaStoreQuery.kt    # MediaStore query helpers
    PhotoPermission.kt    # Permission state models
    PrefsStore.kt         # Encrypted preferences wrapper
    UploadDao.kt          # Upload tracking DAO
    UploadDatabase.kt     # Room-style database (simplified)
    UploadRecord.kt       # Upload entity
  b2/            # Backblaze B2 / S3 integration layer
    S3ClientFactory.kt    # AWS S3 client creation
    S3Config.kt           # S3 configuration
    S3KeyBuilder.kt       # Key/path generation for uploads
    S3Uploader.kt         # Upload orchestration
  ui/            # Activities, Adapters, custom views
    GalleryActivity.kt    # Main gallery screen
    GalleryAdapter.kt     # RecyclerView adapter
    GalleryItem.kt        # Gallery item model
    OnboardingActivity.kt # First-launch onboarding
    SquareFrameLayout.kt  # Custom layout widget
  thumbnail/     # Thumbnail generation
    ThumbnailGen.kt
  MainActivity.kt         # App entry point
  PhotoBackupApp.kt       # Application class
app/src/main/res/layout/  # XML layouts
  activity_gallery.xml
  activity_onboarding.xml
  item_gallery.xml
app/src/test/    # Unit tests (mirrors main structure)
app/src/androidTest/  # Instrumented tests (separate CI job)
```

## Key Dependencies
- `androidx.appcompat:appcompat` - AppCompat support
- `com.google.android.material:material` - Material Design components
- `androidx.recyclerview:recyclerview` - RecyclerView
- `androidx.activity:activity-ktx` - Activity KTX
- `androidx.lifecycle:lifecycle-runtime-ktx` - Lifecycle coroutines
- `aws.sdk.kotlin:s3` - AWS S3 SDK for Kotlin
- `aws.smithy.kotlin:http-client-engine-okhttp` - HTTP client for AWS SDK
- `com.github.bumptech.glide:glide` - Image loading
- `androidx.security:security-crypto` - EncryptedSharedPreferences
- `org.jetbrains.kotlinx:kotlinx-coroutines-android` - Coroutines

## Rules for AI Changes
1. Make minimal changes -- fix only what's needed
2. Preserve existing code style
3. Do not add explanatory comments
4. Do not refactor unrelated code
5. Follow the project's architecture patterns
6. If a test is clearly wrong, fix it; otherwise fix production code
7. Do NOT introduce new dependencies without explicit permission
8. Do NOT convert XML layouts to Compose
9. Do NOT add Hilt/Dagger or other DI frameworks

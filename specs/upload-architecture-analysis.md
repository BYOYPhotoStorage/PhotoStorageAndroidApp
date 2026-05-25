# PhotoStorage Upload Architecture Analysis

## 1. High-Level Architecture

```mermaid
graph TB
    subgraph "Discovery Layer"
        A[InitialBackfillWorker]
        B[UploadForegroundService]
        C[NightlyScanWorker]
        D[GalleryActivity Manual Upload]
    end

    subgraph "Queue Layer"
        E[(SQLite uploads table)]
    end

    subgraph "Upload Layer"
        F[UploadWorker]
        G[S3Uploader]
        H[S3 Bucket]
    end

    subgraph "UI Layer"
        I[GalleryRepository]
        J[GalleryActivity Gallery View]
    end

    A -->|enqueue| E
    B -->|enqueue| E
    C -->|enqueue| E
    D -->|enqueue| E

    E -->|processQueue| F
    F -->|upload| G
    G -->|putObject| H

    E -->|read status| I
    I -->|observe| J

    style B fill:#f9f,stroke:#333
    style E fill:#bbf,stroke:#333
    style F fill:#bfb,stroke:#333
```

### Component Responsibilities

| Component | Role | Source File |
|---|---|---|
| `InitialBackfillWorker` | One-time scan after onboarding; seeds the queue | `worker/InitialBackfillWorker.kt` |
| `UploadForegroundService` | Real-time; ContentObservers on MediaStore trigger scans | `service/UploadForegroundService.kt` |
| `NightlyScanWorker` | Daily catch-up scan at 2am | `worker/NightlyScanWorker.kt` |
| `GalleryActivity` | Manual selection -> direct enqueue | `ui/GalleryActivity.kt` |
| `UploadWorker` | Processes `pending` + `failed retryable` records | `worker/UploadWorker.kt` |
| `GalleryRepository` | Merges MediaStore + DB for the UI | `gallery/GalleryRepository.kt` |

---

## 2. Discovery Paths

```mermaid
flowchart TD
    subgraph "Path A: Initial Backfill"
        A1[FirstBackupActivity] -->|first_backup_since<br>selected_bucket_ids| A2[InitialBackfillWorker]
        A2 -->|scanImages DATE_TAKEN| A3[MediaStoreScanner]
        A2 -->|scanVideos DATE_TAKEN| A3
        A3 -->|find new items| A4[DuplicateDetector]
        A4 -->|not duplicate| A5[UploadRecord STATUS_PENDING]
        A5 -->|insert| A6[(Uploads DB)]
    end

    subgraph "Path B: Real-time Background"
        B1[MediaStore change] -->|triggers| B2[ContentObserver]
        B2 -->|3s debounce| B3[UploadForegroundService]
        B3 -->|last_scan_timestamp<br>selected_bucket_ids| B4[MediaStoreScanner]
        B4 -->|scanImages DATE_ADDED| B5[New items]
        B4 -->|scanVideos DATE_ADDED| B5
        B5 -->|find new items| B6[DuplicateDetector]
        B6 -->|not duplicate| B7[UploadRecord STATUS_PENDING]
        B7 -->|insert| B6A[(Uploads DB)]
    end

    subgraph "Path C: Nightly Catch-up"
        C1[WorkManager 2am] -->|triggers| C2[NightlyScanWorker]
        C2 -->|last_scan_timestamp<br>selected_bucket_ids| C3[MediaStoreScanner]
        C3 -->|scanImages DATE_ADDED| C4[New items]
        C3 -->|scanVideos DATE_ADDED| C4
        C4 -->|find new items| C5[DuplicateDetector]
        C5 -->|not duplicate| C6[UploadRecord STATUS_PENDING]
        C6 -->|insert| C6A[(Uploads DB)]
    end

    subgraph "Path D: Manual Upload"
        D1[User selects in Gallery] -->|LocalOnly items| D2[GalleryActivity.onUploadSelected]
        D2 -->|NO time check<br>NO folder check| D3[UploadRecord STATUS_PENDING]
        D3 -->|insert| D4[(Uploads DB)]
    end

    A6 -->|trigger| E[UploadWorker.processQueue]
    B6A -->|trigger| E
    C6A -->|trigger| E
    D4 -->|processQueueNow| E
```

---

## 3. Time Bound System

```mermaid
flowchart LR
    subgraph "User-Chosen Scope"
        A[first_backup_since] -->|used by| B[InitialBackfillWorker]
        A -->|NOT used by| C[UploadForegroundService]
        A -->|NOT used by| D[NightlyScanWorker]
        A -->|NOT used by| E[Manual Upload]
    end

    subgraph "Incremental Cursor"
        F[last_scan_timestamp] -->|used by| C
        F -->|used by| D
        F -.->|set from dateTaken| F
    end

    subgraph "Query Columns"
        G[DATE_TAKEN] -->|InitialBackfill| B
        H[DATE_ADDED] -->|Service/Nightly| C
        H -->|Service/Nightly| D
    end

    style A fill:#fbb,stroke:#333
    style F fill:#bfb,stroke:#333
```

### Time Bound Behaviors

| Discovery Path | Time Source | Query Column | Respects User Scope? |
|---|---|---|---|
| Initial Backfill | `first_backup_since` | `DATE_TAKEN` | Yes |
| Foreground Service | `last_scan_timestamp` | `DATE_ADDED` (default) | No |
| Nightly Worker | `last_scan_timestamp` | `DATE_ADDED` (default) | No |
| Manual Upload | None | N/A | No |

---

## 4. Folder Selection Flow

```mermaid
flowchart TD
    A[FirstBackupActivity] -->|user selects folders| B[selected_bucket_ids<br>Set<String> of BUCKET_IDs]
    B -->|used by| C[InitialBackfillWorker]
    B -->|used by| D[UploadForegroundService]
    B -->|used by| E[NightlyScanWorker]
    B -->|used by| F[GalleryRepository]
    B -.->|NOT checked by| G[GalleryActivity.onUploadSelected]

    F -->|queries MediaStore| H[Gallery View]
    G -->|takes from adapter| I[Direct enqueue]

    J{selected_bucket_ids<br>is empty?} -->|YES| K[NO FILTER<br>All folders included]
    J -->|NO| L[Filter applied<br>Only selected folders]

    B --> J

    style K fill:#fbb,stroke:#333
    style G fill:#fbb,stroke:#333
```

### Folder Filter Application

```mermaid
sequenceDiagram
    participant Query as MediaStore Query
    participant Filter as bucketSelection()
    participant DB as MediaStore DB

    Note over Filter: GalleryRepository.kt:275<br>MediaStoreScanner.kt:168

    Query->>Filter: bucketIds = emptySet()
    Filter-->>Query: selection = null<br>args = null
    Query->>DB: SELECT * FROM images<br>(no WHERE clause)
    DB-->>Query: ALL folders

    Note over Query: When empty, all folders returned

    Query->>Filter: bucketIds = {"123", "456"}
    Filter-->>Query: selection = "bucket_id IN (?,?)"<br>args = ["123", "456"]
    Query->>DB: SELECT * FROM images<br>WHERE bucket_id IN (?,?)
    DB-->>Query: Only selected folders
```

---

## 5. Upload State Machine

```mermaid
stateDiagram-v2
    [*] --> pending : enqueue

    pending --> uploading : processQueue
    uploading --> uploaded : success
    uploading --> failed : error (retryable)
    uploading --> permanently_failed : auth error

    failed --> uploading : retry (exponential backoff)
    failed --> permanently_failed : max retries (5)

    uploaded --> cloud_deleted : user deletes from cloud
    cloud_deleted --> [*] : hard delete after cleanup

    permanently_failed --> [*] : manual intervention needed

    note right of pending
        UploadDao.STATUS_PENDING
    end note

    note right of uploaded
        UploadDao.STATUS_UPLOADED
        uploaded_at timestamp set
    end note
```

### UploadRecord Lifecycle

| Status | DB Value | Retryable? | Description |
|---|---|---|---|
| Pending | `pending` | N/A | Waiting in queue |
| Uploading | `uploading` | N/A | Currently being processed |
| Uploaded | `uploaded` | No | Successfully on S3 |
| Failed | `failed` | Yes | Temp failure, exponential backoff retry |
| Permanently Failed | `permanently_failed` | No | Auth error or max retries reached |
| Cloud Deleted | `cloud_deleted` | No | Soft-deleted, pending cleanup |

---

## 6. GalleryRepository Data Flow

```mermaid
flowchart TD
    subgraph "Inputs"
        A[MediaStore<br>all local photos]
        B[Uploads DB<br>cloud_view = uploaded + not_deleted]
        C[Uploads DB<br>all records]
    end

    subgraph "loadMerged()"
        D[Get latestUploadedAt] --> E[Match uploaded records to local photos]
        E --> F[Mark matched URIs as claimed]
        A --> G[Filter: dateTakenMs > latestUploadedAt]
        G --> H[Filter: not in claimed URIs]
        H --> I[Create LocalOnly items]
        E --> J[Create Synced/CloudOnly items]
    end

    subgraph "Outputs"
        J --> K[GalleryItem.Synced]
        J --> L[GalleryItem.CloudOnly]
        I --> M[GalleryItem.LocalOnly]
    end

    style D fill:#fbb,stroke:#333
```

### Gallery View Modes

```mermaid
graph LR
    subgraph "Local View"
        A[MediaStore photos] -->|bucket-filtered| B[GalleryItem.LocalOnly]
    end

    subgraph "Cloud View"
        C[DB cloud_view] -->|match local if present| D[GalleryItem.CloudOnly]
    end

    subgraph "Merged View"
        E[Uploaded records] -->|matched| F[GalleryItem.Synced]
        E -->|not matched| G[GalleryItem.CloudOnly]
        H[Local photos > latestUploadedAt] -->|not claimed| I[GalleryItem.LocalOnly]
    end
```

---

## 7. Service Lifecycle

```mermaid
sequenceDiagram
    participant User
    participant GA as GalleryActivity
    participant UFS as UploadForegroundService
    participant CO as ContentObserver
    participant WM as WorkManager

    Note over User: Auto-sync ON
    User->>GA: Toggle auto-upload ON
    GA->>UFS: start()
    UFS->>UFS: onCreate()<br>register ContentObservers
    UFS->>CO: register Images + Video observers
    UFS->>UFS: if lastScanTimestamp == 0<br>handleMediaChange() -> full scan

    Note over User: Taking photos
    CO->>UFS: onChange() -> debounce 3s
    UFS->>UFS: handleMediaChange()<br>scan + enqueue + triggerWorker
    UFS->>UFS: processQueue() -> upload to S3

    Note over User: Manual upload
    User->>GA: Select items + Upload
    GA->>UFS: processQueueNow()
    UFS->>UFS: onCreate() runs again if not bound
    UFS->>CO: register observers again
    UFS->>UFS: triggerWorker() -> upload

    Note over User: Auto-sync OFF
    User->>GA: Toggle auto-upload OFF
    GA->>UFS: stop()
    GA->>WM: cancel nightly_scan
    UFS->>UFS: onDestroy()<br>unregister observers

    Note over WM: System restarts service
    WM->>UFS: START_STICKY restart
    UFS->>UFS: onCreate()<br>NO autoUpload check!
    UFS->>CO: register observers
    CO->>UFS: continues enqueueing!
```

### Service Lifecycle Issues

```mermaid
flowchart TD
    A[UploadForegroundService.onCreate] -->|checks| B{lastScanTimestamp == 0?}
    B -->|YES| C[handleMediaChange<br>full scan all photos]
    B -->|NO| D[skip scan]

    A -->|does NOT check| E{isAutoUploadEnabled?}
    E -->|service started for<br>manual upload| F[registers observers]
    E -->|START_STICKY restart| F
    F --> G[continues enqueueing<br>even if auto-sync OFF]

    style A fill:#fbb,stroke:#333
    style E fill:#fbb,stroke:#333
```

---

## 8. Deduplication Flow

```mermaid
flowchart TD
    A[New MediaItem from scan] -->|check 1| B{filename + size + dateTaken<br>match existing DB record?}
    B -->|YES| C[SKIP - Duplicate]
    B -->|NO| D{check 2}

    D -->|compute SHA-256| E[Hash file content]
    E -->|match existing hash?| F{hash in DB?}
    F -->|YES| C
    F -->|NO| G[INSERT - New item]

    style C fill:#bfb,stroke:#333
    style G fill:#bbf,stroke:#333
```

### DuplicateDetector Logic

| Check | Match Criteria | Cost | Location |
|---|---|---|---|
| 1 | `(filename, size, dateTaken)` | DB query only | `DuplicateDetector.kt:24` |
| 2 | SHA-256 of file content | Full file read | `DuplicateDetector.kt:28` |

---

## 9. Upload Mode Gate

```mermaid
flowchart TD
    A[UploadWorker.processQueue] -->|get mode| B{UploadMode}

    B -->|IMMEDIATE| C{wifiOnly?}
    C -->|YES + not wifi| D[DEFER<br>Waiting for Wi-Fi]
    C -->|NO| E[UPLOAD NOW]

    B -->|SCHEDULED| F{inNightlyWindow?<br>2-3am}
    F -->|YES| E
    F -->|NO| G[DEFER<br>Scheduled batch only]

    B -->|HYBRID| H{unmetered?}
    H -->|YES| E
    H -->|NO| D

    style D fill:#ffb,stroke:#333
    style G fill:#ffb,stroke:#333
    style E fill:#bfb,stroke:#333
```

**Important:** The gate only controls **when** to upload, not **what** to upload. The queue is always populated.

---

## 10. Bug Summary Matrix

### Scope Enforcement by Discovery Path

```mermaid
flowchart LR
    subgraph "Time Bounds"
        direction TB
        A1[InitialBackfill] -->|YES| B1[Enforced]
        S1[Service] -->|NO| R1[Ignored]
        N1[Nightly] -->|NO| R1
        M1[Manual] -->|NO| R1
    end

    subgraph "Folder Filter"
        direction TB
        A2[InitialBackfill] -->|YES| B2[Enforced]
        S2[Service] -->|YES| B2
        N2[Nightly] -->|YES| B2
        M2[Manual] -->|NO| R2[Ignored]
    end

    subgraph "Pending Queue"
        direction TB
        A3[On scope change] -->|NO| R3[Not filtered]
        S3[On folder change] -->|NO| R3
    end

    style R1 fill:#fbb,stroke:#333
    style R2 fill:#fbb,stroke:#333
    style R3 fill:#fbb,stroke:#333
    style B1 fill:#bfb,stroke:#333
    style B2 fill:#bfb,stroke:#333
```

### Specific Bug Root Causes

| Bug | Root Cause | Location |
|---|---|---|
| **Time bounds ignored in manual mode** | `onUploadSelected()` has no time check; gallery shows all local photos | `GalleryActivity.kt:475-488` |
| **Time bounds ignored in background** | Service/Nightly use `last_scan_timestamp` + `DATE_ADDED`, not `first_backup_since` | `UploadForegroundService.kt:164`, `NightlyScanWorker.kt:30` |
| **Photos from other folders uploaded** | Empty `selectedBucketIds` = no filter; manual upload bypasses check | `MediaStoreScanner.kt:168`, `GalleryRepository.kt:276` |
| **Folder changes don't affect pending queue** | Already-queued items are not retroactively filtered | `UploadWorker.kt:57-59` |
| **Pending count persists after auto-sync OFF** | Queue not cleared; service lifecycle leak | `GalleryActivity.kt:197-203`, `UploadForegroundService.kt:74` |
| **Phantom enqueueing after sync OFF** | Service is `START_STICKY` with no `autoUploadEnabled` guard in `onCreate()` | `UploadForegroundService.kt:74`, `UploadForegroundService.kt:124` |
| **Old photos hidden from merged view** | `loadMerged()` filters by `latestUploadedAt` (upload time), not user scope | `GalleryRepository.kt:156` |

---

## 11. Key Files Reference

| File | Purpose |
|---|---|
| `worker/InitialBackfillWorker.kt` | One-time initial scan after onboarding |
| `worker/NightlyScanWorker.kt` | Daily catch-up scan |
| `worker/UploadWorker.kt` | Queue processor; uploads to S3 |
| `worker/UploadModeGate.kt` | Decides whether to upload now or defer |
| `service/UploadForegroundService.kt` | Foreground service with ContentObservers |
| `media/MediaStoreScanner.kt` | MediaStore query abstraction |
| `gallery/GalleryRepository.kt` | Merges MediaStore + DB for UI |
| `ui/GalleryActivity.kt` | Gallery UI + manual upload |
| `data/PrefsStore.kt` | All preference storage |
| `data/UploadDao.kt` | DB access for upload records |
| `data/UploadRecord.kt` | Upload record data class |
| `dedup/DuplicateDetector.kt` | Deduplication logic |
| `b2/S3Uploader.kt` | S3 upload operations |
| `b2/S3KeyBuilder.kt` | S3 key/path generation |

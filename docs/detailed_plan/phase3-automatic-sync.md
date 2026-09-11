# Phase 3 詳細実装計画: Automatic Sync

- 作成日: 2026-09-11
- 対象: Androidの定期収集、自動同期、再試行、再起動復旧
- 前提: Phase 2の実機受け入れ完了済み

## 1. 参照資料とPhase 3の位置付け

| 資料                                                               | 確定済みの前提                                                           | Phase 3での扱い                                                         |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| [implementation-plan.md](../implementation-plan.md)                | Phase 3の目的は手動同期を不要にすること                                  | WorkManager、retry、batch、pending / synced、PC停止後の復旧を完成させる |
| [phase2-acceptance.md](../development/phase2-acceptance.md)        | 実機同期、再送の冪等性、Timeline / Dashboard、required checksはPASS      | PASS済みの経路を回帰させ、無人実行だけを追加する                        |
| [phase2-android-app-usage-mvp.md](phase2-android-app-usage-mvp.md) | `CollectionRepository`、`SyncRepository`、Room、version 1 Sync APIが完成 | ID、payload、ACK、100件batch、PC側冪等性を変更せず再利用する            |
| [architecture.md](../architecture.md)                              | Androidは収集と一時保存、PCは正本保存、通信はTailscale Serve HTTPS       | WorkManagerをRoomと既存同期Repositoryの上に追加する                     |
| [technical-design.md](../technical-design.md)                      | ACK済みIDだけをsyncedにし、失敗時はpendingを維持                         | 自動実行でも同じ状態遷移とerror分類を守る                               |
| [data-model.md](../data-model.md)                                  | Androidは`pending` / `synced`、PC側Factは同期状態を持たない              | 送信対象の状態を増やさず、実行leaseは別tableで管理する                  |
| [toolchains.md](../development/toolchains.md)                      | minSdk 26、compile / target API 36、JDK 17                               | WorkManagerを既存toolchainとversion catalogへ追加する                   |

Phase 2完了時点では、画面の「収集して同期」からUsageStatsを収集し、Roomのpendingを100件ずつPCへ送り、ACKを得たIDだけをsyncedへ更新できる。FastAPI停止、Tailscale切断、同一payload再送でもデータを失わず、PC側のTimeline / Dashboardへ実機データが表示されることも確認済みである。

Phase 3ではこのデータ経路を置き換えない。アプリ画面を開かなくても同じ収集・同期処理が実行され、PCやネットワークが一時的に利用できない場合はRoomへ蓄積し、復旧後に自動送信される状態を作る。

### 1.1 Android公式仕様から採用する制約

- WorkManager `2.11.2`を採用候補とする。2026-09-11時点のstableで、既存のcompile SDK 36 / minSdk 26を満たす。実装Issue開始時に[WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work)を再確認し、`android/gradle/libs.versions.toml`と[toolchains.md](../development/toolchains.md)へ同じPRで固定する。
- 定期workの最小間隔は15分で、実行時刻は正確ではなくOS最適化とconstraintsの影響を受ける。[Defining work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)に従い、15分を「必ず15分ごとに動く」という保証として扱わない。
- 定期workはunique nameで登録し、設定更新時は`ExistingPeriodicWorkPolicy.UPDATE`を使う。既存enqueue時刻を保ち、起動のたびに周期をリセットしない。[ExistingPeriodicWorkPolicy](https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy)を契約の根拠とする。
- 一時障害では`Result.retry()`を返し、WorkManagerのbackoffを利用する。constraintsを実行中に失った場合もworkerは停止され、条件回復後に再実行される。
- `CoroutineWorker`は停止時にcoroutine cancellationが伝播する。`CancellationException`を一般エラーへ変換せず再throwし、ACK前のpendingを維持する。
- WorkManagerはworkを内部DBへ保存し、process終了と端末再起動後に再scheduleする。このため独自の`BOOT_COMPLETED` receiverやAlarmManagerを追加しない。

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
Application起動
  └─ unique periodic collectionを登録 / 更新

15分周期の収集機会（時刻はOS裁量）
  └─ UsageCollectionWorker
       ├─ Usage Accessを確認
       ├─ 既存CollectionCoordinatorでUsageEventsを収集
       ├─ RoomへSessionをpending保存
       └─ unique one-time syncをenqueue

自動同期の実行条件
  └─ NetworkType.CONNECTED && BatteryNotLow
       └─ AppSessionSyncWorker
            ├─ PC URLを実行時にDataStoreから読む
            ├─ 既存SyncRepositoryで100件ずつ送信
            ├─ accepted IDだけsynced
            ├─ 一時障害なら指数backoff
            └─ 復旧後に残りのpendingを送信

手動の「収集して同期」
  └─ 同じCoordinator / Repository / Room leaseを再利用
```

ユーザーが通常どおりAndroidを利用し、life-timelineの画面を毎回開かなくても、確定済みAppSessionがRoomへ退避される。PCが利用可能なときは自動で同期され、PC停止中はpendingが維持される。

### 2.2 実装するもの

- WorkManager runtime、coroutine worker、test helperの固定依存。
- 15分周期のUsageStats定期収集。
- unique periodic workの登録、Application process再生成時の更新、端末再起動後の復旧。
- 収集後・Application起動時・PC URL保存後に起動できるunique one-time自動同期。
- 自動同期のnetwork / battery constraints。
- 一時障害の指数backoffと、恒久障害を無制限に即時retryしない判定。
- 100件batch、acceptedのみsynced、部分成功後の残件再送。
- worker同士、およびworkerと手動操作の競合を防ぐ期限付きRoom lease。
- process deathや強制停止で残ったleaseのstale判定と回収。
- 最終自動収集、最終自動同期、pending、直近の自動処理結果を確認できる最小UI。
- WorkManager unit / instrumentation test、Room migration test、長時間の実機受け入れ。
- Phase 2のSync API、Timeline / Dashboard、Tailscale HTTPS、全required checksの回帰確認。

### 2.3 Phase 3に含めないもの

| 対象                                                   | 実施時期・理由                                                  |
| ------------------------------------------------------ | --------------------------------------------------------------- |
| 写真、MediaStore、thumbnail upload                     | Phase 4。Phase 3のschedulerを将来再利用できる境界だけを用意する |
| 位置情報、foreground service、background location      | Phase 5。UsageStatsの定期処理と権限モデルが異なる               |
| ActivityWatch                                          | Phase 6                                                         |
| 一般的なCollector ON/OFF、同期間隔設定、Wi-Fi only設定 | Phase 7。Phase 3では固定の安全な既定値で完成させる              |
| push通知、常駐通知、foreground worker                  | AppSessionの小さなbatchには不要。10分以内の短いworkにする       |
| exact alarm、15分未満の厳密な周期                      | バッテリー効率とWorkManagerの用途に合わない                     |
| 独自のboot receiver、常駐service                       | WorkManagerの永続scheduleを利用する                             |
| Backend Sync API v2、認証方式、PC側schema変更          | version 1契約とTailscale trust boundaryを維持する               |
| synced Sessionの自動削除、保持期間設定                 | データ量を実測してPhase 7以降で決める                           |
| OEMのforce-stop後も無操作で復旧する保証                | OS制約上保証しない。次回アプリ起動でscheduleを再確認する        |

## 3. 設計判断

### 3.1 収集と同期を別workerにする

単一workerへ`NetworkType.CONNECTED`を付けて収集と同期をまとめると、オフライン中はUsageStatsの収集まで止まる。Phase 3の第一目的はOS内の短期event履歴をRoomへ定期退避することなので、次の二つへ分ける。

| worker                  | 種類            | constraints                  | 主責務                                        |
| ----------------------- | --------------- | ---------------------------- | --------------------------------------------- |
| `UsageCollectionWorker` | unique periodic | なし                         | UsageStatsを読み、確定SessionをRoomへ保存する |
| `AppSessionSyncWorker`  | unique one-time | `CONNECTED`、`BatteryNotLow` | RoomのpendingをPCへ送り、ACKを反映する        |

収集workerにはnetwork、charging、battery-not-lowを付けない。低バッテリー中でも軽量なローカル収集を継続し、取りこぼしを避ける。通信workerだけを低バッテリー時に遅延させ、pendingはRoomへ保持する。

PeriodicWorkRequestはchainへ入れられないため、収集workerが完了時にunique one-time syncをenqueueする。Application起動時にも、既存pendingを回収するため同じsync workをenqueueする。

### 3.2 scheduleの固定値

| 項目                   | 値                                  | 理由                                                                  |
| ---------------------- | ----------------------------------- | --------------------------------------------------------------------- |
| collection unique name | `life_timeline_usage_collection_v1` | 名前を永続契約として扱い、重複登録を防ぐ                              |
| sync unique name       | `life_timeline_app_session_sync_v1` | 同時に一つの自動送信だけを許可する                                    |
| collection interval    | 15分                                | WorkManagerで許可される最小周期。UsageEventsを早めにRoomへ退避する    |
| collection flex        | 5分                                 | 許可される最小flexを使い、OSがまとめて実行できる余地を残す            |
| sync policy            | `ExistingWorkPolicy.KEEP`           | ENQUEUED / RUNNING / retry待ちの同期を重複させない                    |
| periodic update policy | `ExistingPeriodicWorkPolicy.UPDATE` | scheduleのenqueue時刻を維持しながら定義を更新する                     |
| sync backoff           | `EXPONENTIAL`、初期15分             | PC停止を異常な高頻度通信にせず、自動復旧を継続する                    |
| lease TTL              | 15分                                | 通常workerの実行上限より長くし、process death後は次回実行で回収できる |
| batch size             | 100 Session                         | Phase 2契約を維持する                                                 |
| 1 run上限              | 8分または20 batchの早い方           | WorkManagerの停止前に安全に処理を区切る                               |

周期やbackoffを後から変更する場合は、値を一か所の`AutomaticSyncPolicy`へ集約し、テストと実機計測結果を同じPRへ含める。

### 3.3 unique workとRoom leaseの役割を分ける

WorkManagerのunique workは自動worker同士の重複を防ぐが、画面から直接実行する手動処理とは相互排他にならない。また現状の`SyncRepository`の`Mutex`はRepository instanceごとなので、`MainActivity`とworkerが別instanceを作ると競合を防げない。

このため、次の二層で直列化する。

1. WorkManagerのunique nameで同種の自動workを一つにする。
2. Roomの期限付きleaseで、自動と手動を含む全実行入口を直列化する。

leaseはAppSessionの`sync_status`とは別物である。Sessionは引き続き`pending` / `synced`だけを持ち、`syncing`へ一括変更しない。これによりprocess death時も未ACKのSessionがpendingのまま残る。

## 4. Android構成

既存の責務分割を保ち、background workと共通実行入口を追加する。

```text
android/app/src/main/kotlin/com/megane14916/lifetimeline/
├── LifeTimelineApplication.kt
├── data/
│   ├── AppContainer.kt
│   └── local/
│       ├── BackgroundWorkStateEntity.kt
│       └── dao/BackgroundWorkStateDao.kt
├── repository/
│   ├── CollectionCoordinator.kt       # 既存、workerからも利用
│   ├── SyncRepository.kt              # 既存契約、1 run上限を追加
│   └── BackgroundExecutionCoordinator.kt
└── worker/
    ├── AutomaticSyncPolicy.kt
    ├── BackgroundWorkScheduler.kt
    ├── UsageCollectionWorker.kt
    ├── AppSessionSyncWorker.kt
    ├── LifeTimelineWorkerFactory.kt
    └── WorkerResultMapper.kt
```

### 4.1 dependency生成

現状は`MainActivity.createMainViewModel()`がUsageStats Adapter、Repository、device DTO、Retrofit clientを直接組み立てている。workerから同じ構成を安全に使えるよう、Application-level containerへ次を移す。

- `CollectionCoordinator` factory。
- endpointを実行時に受け取る`SyncRepository` factory。
- `LocalDataRepository`と`BackgroundExecutionCoordinator`。
- `WorkerFactory`。
- device name生成。

UIとworkerは同じfactoryを使い、同期payloadやerror分類を複製しない。巨大なDI frameworkは追加せず、既存`AppContainer`を拡張する。

`LifeTimelineApplication`はWorkManagerの`Configuration.Provider`を実装し、productionでは`LifeTimelineWorkerFactory`、testではfake factoryを注入できるようにする。Application `onCreate`からschedulerを呼ぶが、workerの本処理はそこで直接実行しない。

### 4.2 workerの実行契約

`UsageCollectionWorker`:

1. `usage_collection_v1` leaseを取得する。別実行中なら正常終了し、次の周期へ任せる。
2. `CollectionCoordinator.collect()`を1回呼ぶ。
3. SUCCESS / NO_DATAなら結果と時刻を記録する。
4. PERMISSION_DENIEDなら設定要確認として記録し、cursorを進めず`Result.success()`。
5. 一時的なUNAVAILABLEなら記録して`Result.retry()`。
6. 結果にかかわらず既存pendingを送れるよう、endpointが設定済みならunique syncをenqueueする。
7. `finally`で自分のtokenに一致するleaseだけをreleaseする。cancellationは再throwする。

`AppSessionSyncWorker`:

1. `app_session_sync_v1` leaseを取得する。別実行中なら`Result.success()`とし、既存実行へ任せる。
2. PC URLをDataStoreから実行時に読む。未設定なら`configuration_required`を記録して正常終了する。
3. 既存`SyncRepository`を作り、古いpendingから100件ずつ送る。
4. batchごとにlease heartbeatを更新する。
5. ACK済みだけsyncedへ更新する。
6. 8分または20 batchで残件があれば、pendingを残して`Result.retry()`。
7. 結果を§7の表で`success` / `retry` / `failure`へ変換する。
8. `finally`でtoken一致時だけleaseをreleaseする。cancellationは再throwする。

workerは10分を超える長時間処理やforeground化を前提にしない。個人利用で上限へ頻繁に達する場合は、batch数、通信時間、pending増加率を受け入れ記録へ残してから値を調整する。

## 5. Room schema version 2と実行lease

### 5.1 `background_work_state`

Room schemaをversion 2へ上げ、次のtableを追加する。

| 列                     | 型・制約                   | 意味                                                               |
| ---------------------- | -------------------------- | ------------------------------------------------------------------ |
| `work_key`             | TEXT PRIMARY KEY           | `usage_collection_v1`または`app_session_sync_v1`                   |
| `lease_owner`          | TEXT NULL                  | 実行ごとに生成するULID token                                       |
| `lease_acquired_at_ms` | INTEGER NULL               | lease取得時刻                                                      |
| `lease_expires_at_ms`  | INTEGER NULL               | staleとみなせる時刻                                                |
| `last_attempt_at_ms`   | INTEGER NULL               | 自動または手動の直近開始時刻                                       |
| `last_success_at_ms`   | INTEGER NULL               | 正常に完了した直近時刻                                             |
| `last_result`          | TEXT NULL                  | 定義済みの小さなresult code                                        |
| `last_error_kind`      | TEXT NULL                  | `permission` / `network` / `server` / `protocol` / `unavailable`等 |
| `consecutive_failures` | INTEGER NOT NULL DEFAULT 0 | 成功時に0へ戻す診断値                                              |
| `updated_at_ms`        | INTEGER NOT NULL           | 状態更新時刻                                                       |

`last_result`と`last_error_kind`にはendpoint、package名、payload、stack trace、tailnet名を入れない。表示文言はcodeからUI側で生成する。

### 5.2 lease操作

- acquireはRoom transaction内で行い、ownerがnull、期限切れ、または明らかに未来へずれた不正時刻の場合だけ新tokenへ更新する。
- heartbeatとreleaseは`work_key + lease_owner`が一致するときだけ更新する。stale leaseを別実行が回収した後、古い実行が新しいleaseを解放してはならない。
- process death、worker stop、例外でreleaseできなくても、`lease_expires_at_ms <= now`なら次回実行が回収する。
- lease取得中にRoom transactionを開いたままHTTP通信しない。leaseは行の状態であり、長時間のSQLite write lockではない。
- 端末時刻の大幅変更を想定し、`acquired_at_ms > now + 5分`のleaseもstaleとして回収する。時刻変更をSession timestampへ補正する機能はPhase 3へ含めない。
- migrationは既存4 tableと全Sessionを保持し、空の`background_work_state`を追加するだけとする。`fallbackToDestructiveMigration`は使用しない。

### 5.3 schedule状態の正本

scheduleの正本はWorkManager内部DBであり、`background_work_state`へ次回予定時刻やWorkRequest IDを複製しない。アプリUIでは、unique periodic workの存在をWorkManagerから読み、実行履歴はRoomから読む。

## 6. schedulingと起動タイミング

### 6.1 Application起動

`BackgroundWorkScheduler.ensureScheduled()`をApplication `onCreate`で呼ぶ。

```text
enqueueUniquePeriodicWork(
  life_timeline_usage_collection_v1,
  UPDATE,
  PeriodicWorkRequest(15分, flex 5分)
)

enqueueUniqueWork(
  life_timeline_app_session_sync_v1,
  KEEP,
  OneTimeWorkRequest(CONNECTED, BatteryNotLow, exponential backoff)
)
```

同じunique nameを維持し、アプリ起動のたびにcancel / re-enqueueしない。worker classを改名・削除すると永続化済みworkを復元できなくなるため、変更時は旧workを安全に移行する専用PRを必要とする。

### 6.2 収集完了時

収集がSUCCESS / NO_DATA / PERMISSION_DENIED / UNAVAILABLEのどれでも、既存pendingが送れる可能性がある。PC URLが存在する場合はunique syncをenqueueする。`KEEP`により既に実行中・backoff中のsyncを重複させない。

### 6.3 PC URL保存時

URL保存成功後にunique syncをenqueueする。URL自体はWorkRequest inputへコピーせず、worker開始時にDataStoreから読む。設定変更後のworkerが古いendpointへ接続し続けることを防ぐ。

### 6.4 手動操作

「収集して同期」は残す。手動操作はWorkManagerの周期をcancel / replaceせず、共通`BackgroundExecutionCoordinator`から同じlease、CollectionCoordinator、SyncRepositoryを呼ぶ。

別の自動実行がleaseを保持している場合は二重送信せず、「バックグラウンド処理が実行中」と表示して状態を再読込する。ユーザー操作をexpedited workへ変換することはPhase 3の必須要件にしない。

## 7. retry、backoff、failure分類

### 7.1 結果対応表

| 条件                                   | Room / 設定への影響              | Worker結果         | 次の動作                                 |
| -------------------------------------- | -------------------------------- | ------------------ | ---------------------------------------- |
| 収集SUCCESS / NO_DATA                  | cursorと成功時刻を規約どおり更新 | `success`          | 次周期                                   |
| Usage Access未許可                     | cursorとpendingを変更しない      | `success`          | 次周期に再確認、UIで設定要確認           |
| UsageEvents一時UNAVAILABLE             | cursorとpendingを変更しない      | `retry`            | 指数backoff                              |
| sync対象0件                            | 変更なし、成功時刻を記録         | `success`          | 次の収集を待つ                           |
| 全batch ACK                            | acceptedだけsynced               | `success`          | 完了                                     |
| 一部batch ACK後に一時障害              | 成功済みだけsynced、残りpending  | `retry`            | 残件から再開                             |
| DNS、TLS、timeout、接続拒否            | 全未ACKをpending維持             | `retry`            | 指数backoff                              |
| HTTP 408 / 429 / 500 / 503 / その他5xx | 全未ACKをpending維持             | `retry`            | 指数backoff                              |
| HTTP 409 / 422 / その他4xx             | 全未ACKをpending維持             | `failure`          | 即時retryしない。次の定期triggerで再確認 |
| schema不一致、未知ACK、重複ACK         | 全未ACKをpending維持             | `failure`          | protocol errorを表示し、実装修正を待つ   |
| endpoint未設定                         | pending維持                      | `success`          | URL保存時または次回起動時に再評価        |
| lease取得失敗                          | データ変更なし                   | `success`          | 保持者へ任せる                           |
| 1 run上限到達                          | 成功済みだけsynced、残りpending  | `retry`            | backoff後に続きを処理                    |
| cancellation / constraint喪失          | ACK済みまで反映、未ACKはpending  | cancellationを伝播 | WorkManagerが再schedule                  |

`Result.retry()`は一時障害だけに使う。validationやprotocol bugを高頻度で再送し続けない。Periodic collectionは一回の恒久的失敗でschedule自体を削除せず、次周期に再評価できる状態を維持する。

### 7.2 backoffとPC長期停止

- exponential backoffの初期値は15分とする。
- WorkManagerのrun attempt countを独自DBへ複製せず、`consecutive_failures`は利用者向け診断だけに使う。
- PC停止が数日続いてもSessionを削除・synced化しない。
- 復旧後は同じSession IDで古い順に再送する。PCがACK送信前に保存済みでも、Phase 2の冪等性により重複登録されない。
- Androidの一般ネットワークがCONNECTEDでもTailscale、Serve、FastAPI、PC sleepの状態まではconstraintで判定できない。これらはHTTP失敗としてbackoffする。

## 8. UIと診断

既存画面へ次を追加する。

- 自動収集: `有効` / `schedule要確認`。
- 最終自動収集の試行時刻と結果。
- 最終自動同期の試行時刻と成功時刻。
- Pending件数。
- 直近の安全なerror分類。
- background処理中の場合の状態。
- 「収集して同期」ボタンは診断・即時実行用として維持する。

epoch msの生値表示は開発中のみにし、実装時は端末timezoneで読みやすい日時へ変換する。Timelineのデータtimezone規約は変更しない。

通知は追加しない。PC停止中の接続失敗を毎回通知すると日常利用を妨げるため、画面内診断に留める。将来通知を追加する場合も、継続中の同一エラーを毎回通知しない設計を別Issueで行う。

## 9. Backend / Frontend / 通信への影響

### 9.1 Backend

Backendのschema、route、responseは変更しない。`POST /api/v1/sync/app-sessions` version 1へ既存payloadを送る。自動同期の頻度でSQLite busyやTimeline readとの競合が増えないかを既存API / DB testと実機で確認する。

### 9.2 Frontend

Frontendへ新機能は追加しない。既存`pc-core-e2e`で、同期fixtureがTimeline / Dashboardへ一度だけ反映される回帰を維持する。

### 9.3 Tailscale

Phase 2のServe URL、HTTPS検証、ACL、loopback bind、Funnel未使用を維持する。証明書検証無効化、cleartext HTTP、LAN bind、自前tokenをretryのために追加しない。

## 10. 実装タスクと依存関係

各IDは原則1 Pull Request程度とし、親Issue `Phase 3: Automatic Sync`の子Issueとして管理する。

```text
P3-01 WorkManager依存・方針・共通dependency factory
   ├─→ P3-02 Room v2・lease・migration ──────────┐
   └─→ P3-03 Scheduler・unique work ────────────┤
                                                  ↓
                              P3-04 定期収集worker
                                                  ↓
                              P3-05 自動同期worker・retry
                                                  ↓
                              P3-06 手動 / 自動の統合・UI
                                                  ↓
                              P3-07 WorkManager統合test・CI
                                                  ↓
                              P3-08 長時間・障害復旧の実機受け入れ
                                                  ↓
                              P3-09 文書更新・Phase 4引き継ぎ
```

### P3-01: WorkManagerと共通実行基盤を追加する

- **目的:** UIとworkerが同じRepository構成を使えるようにする。
- **作業:** WorkManager `2.11.2`候補を公式release notesで再確認し、runtime KTX / testingをversion catalogへ固定する。`AppContainer`へCollection / Sync factoryを移し、`WorkerFactory`とpolicy定数を追加する。
- **成果物:** dependency、`LifeTimelineWorkerFactory`、共通factory、更新済みtoolchains記録。
- **完了条件:** MainActivity固有の重複組立てが解消され、fake dependencyでCoroutineWorkerをtestでき、既存Android CIが成功する。

### P3-02: Room v2と期限付きleaseを実装する

- **目的:** 手動、自動、process deathを跨いで収集・同期の排他を安全に保つ。
- **依存:** P3-01。
- **作業:** §5のentity / DAO、atomic acquire / heartbeat / release / stale recovery、v1→v2 migration、schema JSONを追加する。
- **成果物:** Room schema version 2、migration、`BackgroundExecutionCoordinator`。
- **完了条件:** 既存app / Session / cursor / open stateを保持し、token不一致release、期限切れ、process再生成、時刻の未来ずれをtestできる。

### P3-03: scheduleとunique workを実装する

- **目的:** 再起動後も一つだけの定期収集と同期triggerを維持する。
- **依存:** P3-01。
- **作業:** `BackgroundWorkScheduler`、stable unique name、15分 / 5分periodic request、sync constraints、UPDATE / KEEP policy、Application起動とURL保存時のenqueueを実装する。
- **成果物:** schedule定義とschedule state取得。
- **完了条件:** Application再生成を繰り返してもactive periodic workが1件で、既存enqueue時刻を不必要にリセットせず、endpointをWorkRequest inputへ保存しない。

### P3-04: UsageStats定期収集workerを実装する

- **目的:** アプリを開かない期間もUsageEventsをRoomへ退避する。
- **依存:** P3-02、P3-03。
- **作業:** `UsageCollectionWorker`、lease、既存CollectionCoordinator呼出し、permission / unavailable判定、結果記録、sync triggerを実装する。
- **成果物:** 定期収集経路、worker unit / instrumentation test。
- **完了条件:** networkなしでも収集でき、未許可時にcursorを進めず、同じevent範囲の再実行でSession IDと件数が変わらない。

### P3-05: 自動同期workerとretryを実装する

- **目的:** pendingを無人で送信し、PC停止や通信断から復旧する。
- **依存:** P3-02〜04。
- **作業:** `AppSessionSyncWorker`、CONNECTED / BatteryNotLow、§7の結果mapping、指数backoff、run budget、batch heartbeat、部分成功後の再開を実装する。
- **成果物:** 自動同期経路、retry classifier、worker / Repository test。
- **完了条件:** transientだけretryし、permanent errorを即時loopせず、全未ACKをpendingに保ち、復旧後に同じIDで同期できる。

### P3-06: 手動処理と自動処理を統合しUIを更新する

- **目的:** 自動化後もユーザーが状態を理解し、即時診断できるようにする。
- **依存:** P3-04、P3-05。
- **作業:** MainViewModelを共通Coordinatorへ接続し、lease競合表示、自動schedule / last run / error / pending表示、URL保存後triggerを追加する。
- **成果物:** 更新済みCompose画面、ViewModel / Compose test。
- **完了条件:** worker実行中の手動tapで二重送信せず、回転・process再生成後に永続状態を表示し、手動経路のPhase 2機能が残る。

### P3-07: WorkManager統合testとCI gateを完成する

- **目的:** schedule、constraints、worker、Roomの結合をPRで回帰検出する。
- **依存:** P3-03〜06。
- **作業:** `work-testing`、`TestListenableWorkerBuilder`、`WorkManagerTestInitHelper` / `TestDriver`を使い、period、constraints、retry、unique work、再起動相当をtestする。既存`android-instrumentation-ci`へ統合する。
- **成果物:** worker unit test、WorkManager instrumentation test、Room migration test、diagnostic artifact。
- **完了条件:** test 0件やskipを成功扱いせず、既存6 required checksを維持し、WorkManager失敗が`android-ci`または`android-instrumentation-ci`を失敗させる。

### P3-08: 長時間利用と障害復旧を実機で受け入れる

- **目的:** emulatorでは保証できないDoze、OEM、Tailscale、再起動を実測する。
- **依存:** P3-05〜07。
- **作業:** §12の24時間以上の代表シナリオを、専用PC DBと実機で実施する。worker時刻、pending推移、battery消費の参考値、復旧時間、Timeline件数を個人情報なしで記録する。
- **成果物:** `docs/development/phase3-acceptance.md`。
- **完了条件:** 通常系の自動収集・自動同期が確認され、AC-01〜17の追加シナリオを必要に応じて追試できる記録がある。Doze、OEM最適化、force-stop中の厳密な無人復旧は非保証として扱う。

### P3-09: 文書を更新しPhase 4へ引き継ぐ

- **目的:** 実装と上位文書、READMEの説明を一致させる。
- **依存:** P3-08。
- **作業:** README、architecture、technical-design、data-model、implementation-plan、toolchainsへ実装済みの値と制約を反映し、Phase 3を完了へ更新する。
- **成果物:** 再現手順、運用・障害切り分け、Phase 4へのscheduler拡張点。
- **完了条件:** 正常系の実装値と運用手順が各文書から追跡でき、Phase 2の「手動のみ」という古い説明が残っていない。未実施の拡張実機試験は非保証・任意シナリオとして明記され、Phase 4の開始条件と残課題が分離されている。

## 11. テスト・CI計画

### 11.1 pure unit test

- retry classifierがnetwork、TLS、timeout、408、429、5xxをtransientへ分類する。
- 409、422、その他4xx、schema / ACK不正をpermanentへ分類する。
- cancellationをcatchしてfailureへ変換しない。
- 15分interval、5分flex、constraints、backoff、stable unique nameをpolicy testで固定する。
- 100件batch、複数batch、8分 / 20 batch budget、部分成功後の残件数。
- permission denied / no data / unavailableとWorker Resultの対応。
- URLを実行時に読むため、enqueue後の変更が次のworkerへ反映される。
- MainViewModelのworker実行中、設定要確認、自動成功、自動失敗、手動即時実行の表示。

### 11.2 Room / instrumentation test

- v1 fixture DBを作り、pending / synced / cursor / open activityを保持したままv2へmigrationする。
- leaseの初回取得、同時取得拒否、heartbeat、token一致release、token不一致release拒否。
- TTL後のstale回収、未来へずれたleaseの回収、DB close / reopen後の回収。
- worker stop相当でleaseが残っても次回実行が回収する。
- workerと手動処理を同時開始しても同一Sessionを二重送信せず、ACK済み更新数がprotocol errorにならない。
- WorkManagerTestInitHelperでperiod delay、constraints成立 / 不成立、retry状態を再現する。
- Application相当のschedule呼出しを複数回行ってもunique periodic workが1件である。
- periodic collection実行後にunique syncが最大1件enqueueされる。

WorkManager公式の[Integration tests](https://developer.android.com/develop/background-work/background-tasks/testing/persistent/integration-testing)に従い、worker単体は`TestListenableWorkerBuilder`、schedule / constraints / periodは`WorkManagerTestInitHelper`と`TestDriver`で分けて検証する。

### 11.3 Backend / Frontend回帰

- version 1 Sync APIの1件、100件、同一再送、競合rollback、Timeline / Dashboard反映testを維持する。
- 自動同期を模した複数requestでもDB件数と`created_at_ms`が増殖しない。
- Timeline GETとsync POSTの並行、SQLite busy→503、Android retry分類を対応させる。
- FrontendにはWorkManagerのmockを導入せず、PC Coreの実DB E2Eを維持する。

### 11.4 CI gate

- `frontend-ci`
- `backend-ci (ubuntu)`
- `backend-ci (windows)`
- `android-ci`
- `pc-core-e2e`
- `android-instrumentation-ci`

既存6 checksをrequiredのまま維持する。新しいworkflow名をむやみに追加せず、WorkManager unit testは`android-ci`、Room / scheduler integrationは`android-instrumentation-ci`へ入れる。emulator失敗時はJUnit、logcat、Room schema、WorkInfoの安全な要約をartifact化する。endpoint、device ID、package利用履歴はartifactへ含めない。

## 12. 実機受け入れ手順と完了条件

### 12.1 専用環境

1. clean checkoutで既存全check相当を実行する。
2. Phase 3専用の`LIFE_TIMELINE_DATA_DIR`へmigrationを適用する。
3. Backendを`127.0.0.1:8000`、Frontendを`127.0.0.1:5173`で起動する。
4. Tailscale ServeをFunnelなしで設定し、AndroidとPCを同じtailnetへ参加させる。
5. debug APKを実機へinstallまたはupgradeし、Phase 2のRoom v1データを保持したv2 migrationも別シナリオで確認する。
6. Usage AccessとPC URLを設定し、WorkManagerのunique workを`dumpsys jobscheduler`、WorkInfo、画面表示で確認する。

### 12.2 代表シナリオ

1. 画面を閉じたまま複数アプリを利用し、periodic worker後にRoomへSessionが保存されることを確認する。
2. PCを起動した状態で、手動ボタンを押さずにpendingが0へ減り、Timeline / Dashboardへ表示されることを確認する。
3. Backendを停止して新しいSessionを作り、pendingが維持され、指数backoffへ入ることを確認する。
4. Backendを復旧し、手動操作なしでpendingが同期され、PCに重複がないことを確認する。
5. Tailscaleを切断し、Wi-Fi↔mobile networkを切り替え、再接続後に自動復旧することを確認する。
6. Androidを再起動し、アプリ画面を開かずに既存scheduleとpending送信が復旧することを確認する。
7. worker送信中にprocessを終了し、ACK前のSessionがpending、ACK済みSessionがsyncedで、stale lease回収後に続行できることを確認する。
8. 自動workerと「収集して同期」を近接実行し、二重送信・不正ACK表示・cursor後退がないことを確認する。
9. Usage Accessを取り消し、cursorが進まず設定要確認となり、既存pendingの同期は継続できることを確認する。
10. Battery Saver / 低バッテリー中はsyncが遅延しても収集は継続し、解除後に送信されることを確認する。
11. 24時間以上アプリ画面を開かず通常利用し、収集間隔の実測、欠落、pending推移、参考battery消費を記録する。
12. 同じ期間を再収集・再送してSession ID、PC DB件数、Timeline / Dashboard集計が変わらないことを確認する。

### 12.3 受け入れチェックリスト

| ID    | 完了条件                                                                    | 主な証拠                                    |
| ----- | --------------------------------------------------------------------------- | ------------------------------------------- |
| AC-01 | Phase 2の全受け入れ条件と6 required checksが回帰していない                  | CI、Phase 2 smoke                           |
| AC-02 | unique periodic collectionが15分 / 5分flexで一つだけ登録される              | scheduler test、WorkInfo                    |
| AC-03 | アプリ画面を開かずUsageEventsがRoomへ定期退避される                         | 実機24時間記録、Room確認                    |
| AC-04 | collectionはnetwork / battery状態により不必要に止まらない                   | constraints test、実機offline / low battery |
| AC-05 | syncはCONNECTEDかつBatteryNotLowで実行される                                | WorkManager test、実機切替                  |
| AC-06 | 収集後、起動時、URL保存後にunique syncが最大一つenqueueされる               | scheduler integration test                  |
| AC-07 | 一時障害だけが指数backoffされ、恒久障害は即時loopしない                     | Worker Result / run attempt test            |
| AC-08 | PC停止、sleep、FastAPI停止、Tailscale切断中も全未ACK Sessionがpendingで残る | 障害試験、Room件数                          |
| AC-09 | 復旧後に手動操作なしで残件が古い順に同期される                              | 実機復旧記録、PC DB                         |
| AC-10 | ACK済みだけsyncedとなり、部分成功後は残件から続行する                       | Repository / worker test                    |
| AC-11 | 同一ID再送でPCの件数、`created_at_ms`、Timeline / Dashboard集計が増殖しない | API / E2E / 実機確認                        |
| AC-12 | 端末再起動・process再生成後にperiodic workとretry中workが復旧する           | 実機再起動、WorkInfo                        |
| AC-13 | manual / automatic / worker同士がRoom leaseで直列化される                   | concurrency test、実機近接操作              |
| AC-14 | process death後のstale leaseを回収し、未ACKを再送できる                     | Room test、process kill試験                 |
| AC-15 | Usage Access取消時にcursorを進めず、既存pending同期を失わない               | worker / 実機permission試験                 |
| AC-16 | UIでschedule、最終自動収集 / 同期、pending、直近errorを確認できる           | Compose test、実機画面                      |
| AC-17 | 24時間以上の通常利用で、手動操作なしにPCへデータが蓄積される                | Phase 3受け入れ記録                         |

正常系の自動収集・自動同期、全required checks、WorkManager / Room統合テスト、Phase 3受け入れ手順の反映をPhase 3実装完了の条件とする。Doze、OEM最適化、24時間以上の長時間試験などOS・実機依存の追加確認は、`docs/development/phase3-acceptance.md`の任意シナリオとして別管理する。

## 13. 非保証と運用上の注意

- 15分は実行期限ではない。Doze、OEM最適化、constraint、端末電源OFFにより遅延・skipし得る。
- ユーザーがアプリをforce-stopした場合、OSがbackground workを停止することがある。次回起動でscheduleを再確認するが、force-stop中の無人復旧は完成条件にしない。
- Usage Access取消、アプリdata消去、uninstall、OSがUsageEventsを保持しなかった期間は復元できない。
- `CONNECTED`はPCやTailscaleの到達性を保証しない。HTTP結果まで確認して初めて同期成功とする。
- 自動同期成功はPC SQLiteへの保存とACK受信を意味し、Androidのsynced行を削除することは意味しない。
- 端末timezone変更はTimeline表示へ影響するが、収集・同期timestampは引き続きUTC epoch msで保持する。

## 14. リスクと対策

| リスク                                     | 対策                                                  | 受け入れでの確認            |
| ------------------------------------------ | ----------------------------------------------------- | --------------------------- |
| network constraintが収集も止める           | workerを収集と同期へ分離する                          | offline中もRoom件数が増える |
| Application起動ごとにperiodic workが増える | stable unique name + UPDATE                           | active workが常に1件        |
| workerと手動同期が同じpendingを送る        | Room lease + PC冪等性                                 | concurrency testと近接操作  |
| process deathでleaseが残る                 | TTL、token付きrelease、stale recovery                 | kill後の再実行              |
| ACK前に通信が切れる                        | pending維持、同一ID再送                               | DB件数が増えない            |
| PC長期停止で高頻度retryする                | 15分初期の指数backoff                                 | run attemptとbattery参考値  |
| 恒久的な409 / 422を無限retryする           | failure分類し、次の定期triggerまで待つ                | request回数test             |
| 大量pendingでworker上限を超える            | 100件batch、8分 / 20 batch budget、続きからretry      | 大量fixture                 |
| WorkManager内部状態と独自状態がずれる      | scheduleはWorkManager、履歴 / leaseだけRoomを正とする | 再起動・DB reopen test      |
| worker classの改名で永続workが復元不能     | class / unique nameを永続契約にし、変更時は移行する   | upgrade test                |
| OEMがbackground実行を遅延する              | exact性を非保証とし、24時間実測を残す                 | 複数状態の実機記録          |
| ログへ個人の利用履歴が出る                 | result codeだけを記録し、payloadを出力しない          | artifact review             |

## 15. Phase 4への引き継ぎ

Phase 4の写真同期では、Phase 3で確定したscheduler、WorkerFactory、期限付きlease、safe diagnostics、retry classifierの考え方を再利用できる。ただし写真はファイル転送、容量、Wi-Fi only、原本非送信、thumbnailのatomic保存が必要なため、AppSessionのworkerへ同居させない。

Phase 3完了時に、次をPhase 4へ引き渡す。

- Application再生成・端末再起動に耐えるunique work登録方式。
- ローカル収集とnetwork uploadを分ける設計。
- manual / automaticを直列化するlease API。
- transient / permanent errorとbackoffの共通分類方針。
- WorkManager unit / instrumentation / 実機長時間試験の雛形。
- 個人情報を含めないworker診断と受け入れ記録の形式。

Phase 3で未実施のDoze、OEM最適化、force-stop、24時間以上の実機試験は、写真同期の設計を確定する前に必要に応じて再確認する。Phase 4では写真の容量、送信頻度、Wi-Fi制約、原本保持方針を実測値に基づいて別途決定し、既存AppSessionのunique work名・Room lease・retry分類を変更しない。

写真用のwork name、batch、constraints、lease key、retry上限はPhase 4のデータ量を基に別途決定する。

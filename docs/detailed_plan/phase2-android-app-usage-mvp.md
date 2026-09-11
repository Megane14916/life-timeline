# Phase 2 詳細実装計画: Android App Usage MVP

- 作成日: 2026-09-09
- 対象: Androidのアプリ利用履歴収集、Roomへの永続化、手動同期、Tailscale経由のSync API、既存Timelineへの統合
- 状態: 実装・実機受け入れ済み。結果は[Phase 2受け入れ記録](../development/phase2-acceptance.md)を参照
- 完成条件: Android実機で発生した確定済みAppSessionをRoomへ保存し、手動同期によってTailscale経由でPCへ送り、同じIDの再送で二重登録せずReact TimelineとDashboardから閲覧できる

## 1. 参照資料とPhase 2の位置付け

本計画はrepository内の全ドキュメントと、Phase 1完了時点の実装を確認して具体化した。上位文書の方針を維持しつつ、選択肢・例・後続課題として残っていた同期契約とSession生成規約をPhase 2の範囲で確定する。

| 文書                                                        | Phase 2に関係する方針                                                                              | 本計画への反映                                                                               |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| [README.md](../../README.md)                                | Phase 2のAndroid Collector・手動診断・Tailscale Serveを実機手順へ反映                           | 起動・migration・Timeline / Statistics・required checksと実機受け入れ手順を一つにする          |
| [overview.md](../overview.md)                               | PCをデータ管理の中心、Androidを収集と一時保存、同期はTailscale                                     | Roomは送信待ちデータを保持し、PCのNormalized Dataを長期保存の正とする                        |
| [product-spec.md](../product-spec.md)                       | 収集→Room→PC→ACK、PC停止中も記録継続、MVP受け入れ条件1〜8                                          | 手動同期でもACK受信前に削除せず、実機から既存Timelineまでを受け入れる                        |
| [architecture.md](../architecture.md)                       | Kotlin / Compose / Room / UsageStatsManager / Retrofit・OkHttp、Tailscale Serve、FastAPIはloopback | Android内部の責務分割とHTTPS経路をこの構成へ揃える                                           |
| [data-model.md](../data-model.md)                           | 発生元でID生成、UTC epoch ms、共通`app_sessions`、Android側の同期状態                              | AndroidでもPCと同じAppSession ID・時刻・durationを持ち、PC側Factへ同期状態を追加しない       |
| [technical-design.md](../technical-design.md)               | accepted IDだけを同期済みにする、再送可能、独自TLS・暗号を作らない                                 | version付きバッチ契約、ACK照合、Tailscale HTTPS、失敗時pending維持を定義する                 |
| [implementation-plan.md](../implementation-plan.md)         | Phase 2はRoom、UsageStats、Session生成、手動Sync、Sync API、Tailscale、Timeline表示                | この7項目をPhase 2の必須範囲とする                                                           |
| [phase0-project-setup.md](phase0-project-setup.md)          | Android機能依存は使用フェーズで導入し、全projectのCIを維持                                         | Room等はPhase 2で初めて追加し、version catalogへ固定する                                     |
| [phase1-pc-core.md](phase1-pc-core.md)                      | AppSession ID、自然キー、冪等性、日跨ぎ、同期への引き継ぎ                                          | 表示用APIを同期payloadへ流用せず、既存Repositoryへ正規化して保存する                         |
| [phase0-acceptance.md](../development/phase0-acceptance.md) | Android雛形と固定toolchainをclean checkoutで確認済み                                               | 既存Gradle / JDK / SDK / lint / build経路を拡張する                                          |
| [phase1-acceptance.md](../development/phase1-acceptance.md) | AC-01〜13完了、同期・ACK・再送・CollectorがPhase 2への引き継ぎ                                     | `main`のPC Coreを開始点とし、既存固定値とE2Eを回帰させる                                     |
| [toolchains.md](../development/toolchains.md)               | minSdk 26、compile / target API 36、JDK 17、version更新規則                                        | OS分岐はAPI 26〜36を対象とし、新規依存のversionをcatalogと文書へ同じPRで記録する             |

Android公式仕様ではUsageStatsのevent履歴は端末内に数日しか保持されず、`PACKAGE_USAGE_STATS`の宣言だけでなくSettingsから利用状況へのアクセス許可が必要である。またAndroid 10 / API 29以降は`ACTIVITY_RESUMED` / `ACTIVITY_PAUSED`を使い、それ以前は同じ意味の旧event名を扱う。Roomは初版からschemaをexportしてGit管理し、破壊的fallbackを使わない。Tailscale ServeはPCのloopback HTTPをtailnet内のHTTPSへreverse proxyし、`--bg`利用時は再起動後も設定を再開できる。実装開始時にも各公式資料の現行記述を再確認する。

### 1.1 文書間に残る記述差の扱い

- `technical-design.md`§4の`device_id + event_id`ではなく、Phase 1で実装済みの`app_sessions.id`単独PRIMARY KEYを正とする。Androidは端末間でも衝突しないULIDを一度だけ生成する。
- `technical-design.md`の`pending / synced`をPhase 2で実装する。`syncing`、WorkManager、無人retry、network constraint、backoffはPhase 3へ残す。送信中はUI上の一時状態として表現し、Roomの永続状態にはしない。
- Architecture図のRoom→WorkManagerはPhase 3完成時の全体像である。Phase 2では画面上の`Sync now`操作から同じRepositoryを直接呼ぶ。
- Phase 1の`created_at_ms`はPCで最初に保存した時刻であり、Androidが送る値ではない。Android側では別名の`collected_at_ms`をローカル管理に使う。
- Timeline / StatisticsのGET契約、日跨ぎ処理、Frontend表示はPhase 1の実装をそのまま利用する。Phase 2の同期payloadへ`TimelineItem`、`display`、Statisticsレスポンスを流用しない。
- `apps(platform, identifier)`をMasterの自然キーとし、Android package nameを`identifier`にする。同一自然キーがPCにあればPC側の既存`app_id`を再利用してよい。Android側のapp IDとPC側のcanonical app IDが一致することを同期成功条件にしない。

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
Android OS UsageEvents
        ↓ 利用状況へのアクセス許可を確認
Sessionizer
        ↓ 終了が確定した区間だけを生成
Room
  apps / app_sessions(pending) / collector_state
        ↓ ユーザーが「収集して同期」を実行
HTTPS: Tailscale Serve
        ↓
POST /api/v1/sync/app-sessions
        ↓ 1 batchをtransaction保存
SQLite: devices / apps / app_sessions
        ↓ accepted IDs
Room: accepted分だけsynced
        ↓
既存Timeline / Dashboard
```

Phase 2の価値は、実機で発生したデータが初めてPC Coreへ到達することである。デモfixtureの置換やAndroid画面上だけの表示では完了としない。

### 2.2 実装するもの

- Usage Accessの宣言、権限状態判定、Settings画面への案内と復帰後の再判定。
- `UsageStatsManager.queryEvents`を境界付きで呼ぶCollector。
- foreground / background eventから確定済みAppSessionを作る純粋なSessionizer。
- 端末ID、Android app Master、AppSession、Collector cursor / open stateを保持するRoom DB。
- AppSession生成時のULID付与、再処理時の同一Session照合、Room transaction。
- pending件数、最終収集、最終同期、最後のエラーを確認できる最小Compose画面。
- 設定済みPC URLに対する手動の収集・バッチ同期。
- version付き`POST /api/v1/sync/app-sessions`と、既存Repositoryを使った冪等・atomic保存。
- Tailscale Serveによるtailnet内HTTPS接続のWindows / Android手順。
- ACKを受けたIDだけをRoomでsyncedへ変更し、失敗・中断時はpendingを維持する処理。
- 同期後に既存React Timeline / Dashboardへ実機データが現れることの確認。
- Backend、Android、実DB E2E、実機Tailscale経路のテストと受け入れ記録。

### 2.3 Phase 2に含めないもの

| 対象                                                                        | 実施時期・理由                                                    |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| WorkManager、定期同期、自動retry、指数backoff、network / battery constraint | Phase 3。Phase 2は失敗後にユーザーが再度Syncを押す                |
| 永続的な`syncing` lease、複数worker、並列upload                             | Phase 3。Phase 2は単一画面操作をmutexで直列化する                 |
| 端末再起動直後の自動収集・自動同期                                          | Phase 3。Roomデータ自体は再起動後も保持する                       |
| 写真、動画、MediaStore、thumbnail                                           | Phase 4                                                           |
| 位置情報、background location、PlaceVisit、Map                              | Phase 5                                                           |
| ActivityWatch、PC固有details                                                | Phase 6                                                           |
| Device管理、一般Settings画面、Filter / Search、Export / Backup              | Phase 7。Phase 2では同期に必要なPC URLだけを設定できる            |
| 独自アカウント、API key、TLS、暗号化DB                                      | MVP非目標。tailnet ACL、Tailscale HTTPS、OSの端末暗号化を利用する |
| Android上の履歴Timeline                                                     | PC Web UIを閲覧先とし、Androidは収集・同期状態に集中する          |

Phase 2では「PC停止中でも、既にRoomへ保存したpending Sessionを失わない」ことを保証する。UsageStatsのOS内event保持期間を超えてアプリを一度も起動しなかった場合まで保証するには定期収集が必要なため、Phase 3でWorkManagerによる定期収集・同期を追加する。

## 3. 開始条件と採用技術

### 3.1 開始条件

- [Phase 1受け入れ記録](../development/phase1-acceptance.md)のAC-01〜13とrequired checksが成功している。
- 作業開始時の`main`でBackend / Frontend / Androidの既存検証が成功する。
- Android実機はAPI 26以上で、Google Play版等のTailscaleへログインできる。
- Windows PCとAndroid実機を同じtailnetへ参加させられ、ACLを編集または確認できる。
- 実機検証では個人の既存`lifelog.db`を使わず、Phase 2専用データディレクトリを使う。
- 各実装タスクは技術設計§14に従い、Issue→branch→PR→CI→mergeの単位で進める。

### 3.2 採用案

| 項目               | 採用案                                             | 理由・制約                                                        |
| ------------------ | -------------------------------------------------- | ----------------------------------------------------------------- |
| Android永続化      | Room + KSP、schema export有効                      | DAOを境界にし、将来のmigrationを検証可能にする                    |
| 非同期処理         | Kotlin Coroutines、ViewModelのscope                | 画面回転とmain thread blockingを避ける。WorkManagerはまだ使わない |
| HTTP               | Retrofit + OkHttp + Kotlin serialization converter | version付きJSON契約とtimeout / error分類を型で扱う                |
| 設定保存           | DataStore Preferences                              | stable device ID、PC base URL、表示用最終結果を小さく保存する     |
| ID                 | 26文字・大文字のULID                               | Phase 1のvalidatorと主キー契約へ一致させる。通信時に再生成しない  |
| 時刻               | `System.currentTimeMillis()`基準のUTC epoch ms     | Android・Room・同期payload・PC DBで整数ミリ秒を維持する           |
| 同期単位           | 最大100 Session、古い順、逐次batch                 | 技術設計の例に合わせ、SQLiteのwrite transactionを短く保つ         |
| Backend書込        | 1 request = 1 transaction、all-or-nothing          | ACKとRoom状態を単純にし、部分保存の曖昧さを避ける                 |
| PC接続             | `https://<pc-name>.<tailnet>.ts.net`               | Tailscale Serveの証明書とACLを使い、FastAPIはloopbackを維持する   |
| Android DB同期状態 | `pending` / `synced`                               | 送信中はin-memory。process deathで永続的な`syncing`が残らない     |

新規dependencyのversionは実装Issue開始時に公式互換性を確認し、`android/gradle/libs.versions.toml`と必要なGradle plugin設定へ固定する。既存toolchainを暗黙に更新せず、更新が必要なら理由・文書・CIを同じPRへ含める。

## 4. Androidの責務とディレクトリ案

既存のsingle-activity Composeアプリを次の責務へ分ける。名称は実装時に既存packageへ合わせて調整できるが、OS API、Session化、永続化、HTTP、UIを1クラスへまとめない。

```text
android/app/src/main/kotlin/com/megane14916/lifetimeline/
├── MainActivity.kt
├── data/
│   ├── local/
│   │   ├── LifeTimelineDatabase.kt
│   │   ├── AppEntity.kt
│   │   ├── AppSessionEntity.kt
│   │   ├── CollectorStateEntity.kt
│   │   ├── OpenActivityEntity.kt
│   │   └── dao/
│   ├── preferences/
│   │   └── AppPreferences.kt
│   └── remote/
│       ├── SyncApi.kt
│       └── SyncDtos.kt
├── domain/
│   ├── AppSession.kt
│   ├── UsageEventRecord.kt
│   └── SyncResult.kt
├── collector/
│   ├── UsageAccessChecker.kt
│   ├── UsageEventsCollector.kt
│   └── AppSessionizer.kt
├── repository/
│   ├── CollectionRepository.kt
│   └── SyncRepository.kt
└── ui/
    ├── MainScreen.kt
    └── MainViewModel.kt

android/app/schemas/              # Room schema JSONをGit管理
android/app/src/test/             # 純粋Kotlin / coroutine / HTTP変換test
android/app/src/androidTest/      # Room / DataStore / integration test
```

`UsageEvents.Event`を直接Sessionizerへ渡さず、必要フィールドだけを持つ`UsageEventRecord`へAdapterで変換する。これによりOSなしのunit testでevent列を網羅する。UIはDAOやRetrofitを直接呼ばず、Repositoryがtransaction、ACK照合、error分類を担当する。DI frameworkはこの規模では追加せず、Application-level containerまたは明示的factoryで依存を組み立てる。

## 5. Androidの永続モデル

### 5.1 端末identityと設定

- 初回起動時にdevice ULIDを1回生成し、DataStoreへ保存する。再起動、アプリ更新、同期再試行では変更しない。
- device表示名の初期値はmanufacturer / modelから作り、空の場合は`Android device`とする。Phase 2では編集UIを持たない。
- Android ID、IMEI、広告ID等を端末主キーに使わない。
- PC base URLはユーザーがTailscale ServeのHTTPS URLを入力して保存する。末尾slashを正規化し、`https`以外、userinfo、query、fragmentを拒否する。
- アプリデータ消去・再install後は新しいdevice IDになる。旧deviceとの統合はPhase 7のDevice管理へ残す。

### 5.2 Room schema version 1

#### `android_apps`

| 列              | 型・制約             | 意味                                            |
| --------------- | -------------------- | ----------------------------------------------- |
| `id`            | TEXT PK、ULID        | Androidで一度生成したapp Master ID              |
| `package_name`  | TEXT NOT NULL UNIQUE | PCの`apps.identifier`になるpackage name         |
| `display_name`  | TEXT NOT NULL        | PackageManagerのlabel。取得不能時はpackage name |
| `updated_at_ms` | INTEGER NOT NULL     | labelを最後に確認した時刻                       |

#### `android_app_sessions`

| 列                | 型・制約             | 意味                                                  |
| ----------------- | -------------------- | ----------------------------------------------------- |
| `id`              | TEXT PK、ULID        | PCへそのまま送るLifeTimeline AppSession ID            |
| `app_id`          | TEXT NOT NULL FK     | `android_apps.id`                                     |
| `started_at_ms`   | INTEGER NOT NULL     | UTC epoch ms                                          |
| `ended_at_ms`     | INTEGER NOT NULL     | 開始より後                                            |
| `duration_ms`     | INTEGER NOT NULL     | 終了−開始と一致                                       |
| `source`          | TEXT NOT NULL        | Phase 2では`android_usage_stats`                      |
| `source_key`      | TEXT NOT NULL UNIQUE | 同じsource区間を再処理したとき元のIDを見つける照合key |
| `sync_status`     | TEXT NOT NULL        | `pending` / `synced`                                  |
| `collected_at_ms` | INTEGER NOT NULL     | AndroidでRoomへ保存した時刻                           |
| `synced_at_ms`    | INTEGER NULL         | accepted ACKを反映した時刻                            |

`CHECK(ended_at_ms > started_at_ms)`と`CHECK(duration_ms = ended_at_ms - started_at_ms)`を置き、`(sync_status, started_at_ms, id)`へpending取得用indexを置く。synced行も削除せず、再収集の照合と端末内履歴として保持する。保持・削除設定は実測後の別Phaseで検討する。

#### `collector_state`

| 列                     | 型・制約         | 意味                                              |
| ---------------------- | ---------------- | ------------------------------------------------- |
| `collector`            | TEXT PK          | `android_usage_stats_v1`                          |
| `cursor_at_ms`         | INTEGER NOT NULL | transactionで処理済みにした最後の時刻             |
| `cursor_key`           | TEXT NOT NULL    | 同一timestampのeventを安定してskipするtie-breaker |
| `last_collected_at_ms` | INTEGER NOT NULL | 最後に収集transactionが成功した時刻               |

#### `open_activities`

終了eventをまだ得ていないforeground状態を次回収集へ引き継ぐ。package name、class name（nullは空文字へ正規化）、開始時刻、開始event keyを保持し、packageに属するactive activityが0件から1件になった時刻をSession開始として扱う。画面offやshutdown等で閉じられなかった不確実な状態は後述の規約で破棄する。

Roomのschema JSONを初版からcommitし、`fallbackToDestructiveMigration`を使わない。Phase 2中にschema versionを上げる場合もmigrationとデータ保持testを同じPRへ含める。

### 5.3 atomicityと再処理

1回の収集では、app Masterのupsert、確定Sessionのinsert、open stateの更新、cursor更新を1つのRoom transactionで行う。process deathや例外で途中まで進んだ場合はcursorだけを進めない。

`source_key`は`collector version + device ID + package name + started_at_ms + ended_at_ms`から決定的に作る。これは認証や暗号用途ではなく再処理照合用である。同じkeyがRoomにあれば既存行と内容を比較し、一致時は既存ULIDとsync状態を維持する。不一致時は上書きせずcollector errorとして記録する。これにより、cursor境界の再読込や手動収集の連打で同じ区間へ別ULIDを付けない。

## 6. UsageStats収集とAppSession生成規約

### 6.1 権限とquery範囲

- Manifestへ`android.permission.PACKAGE_USAGE_STATS`を宣言する。
- 通常のruntime permission dialogではなく、`Settings.ACTION_USAGE_ACCESS_SETTINGS`へ案内する。
- 画面表示時、Settingsからの復帰時、収集直前にAppOpsManagerと実query結果で許可を再確認する。
- 未許可、端末lock中等で`queryEvents`が利用できない場合はcursorを進めず、Roomのpendingを変更しない。
- 初回は`now - 7日`から`now`を要求する。ただしOSが保持している数日分だけが取得可能であることをUIとREADMEへ明記する。
- 2回目以降は保存済みcursorをbeginに含め、`[begin, now)`で取得する。同一timestampのeventは`cursor_key`までskipし、正常transaction後だけcursorを更新する。
- eventはtimestamp、package、class、event typeから作る安定keyで決定順に並べる。入力順だけに依存しない。
- life-timeline自身のpackageは収集対象から除外し、同期画面の操作をライフログへ混ぜない。

### 6.2 Sessionizer

Sessionizerは次のeventだけを利用する。

- API 29以上: `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED`。
- API 26〜28: `MOVE_TO_FOREGROUND` / `MOVE_TO_BACKGROUND`。
- API 28以上: `SCREEN_NON_INTERACTIVE`でその時点のopen activityを閉じる。
- API 29以上: `DEVICE_SHUTDOWN`でopen stateを終了扱いにし、`DEVICE_STARTUP`時に残存した不確実なopen stateを破棄する。

規約:

1. package + classをactivity keyとし、同じkeyの重複resumeは新しいSessionを作らない。
2. package内のactive activity数が0→1になった時刻をpackage Sessionの開始とする。
3. pauseで最後のactive activityがなくなった時刻を終了とする。Activity切替で別classが同時刻にresumeしていればpackage Sessionを分割しない。
4. endだけが現れたevent、時刻が逆転するevent、空packageはSessionにせずdiagnostic countへ記録する。
5. 終了が未確定の区間は`open_activities`に残し、`app_sessions`へは書かない。
6. 開始と終了が同じ、または終了が開始より前の区間は保存しない。
7. 確定区間を勝手に日付境界で分割しない。PC Backendが表示日ごとの`display`を計算する。
8. 隣接Sessionの自動merge、AFK控除、screen timeとの重複除去は行わない。
9. PackageManagerでlabelを取得し、取得不能・空文字ならpackage nameを表示名にする。

複数window、OEM固有event、Activityの急速な切替は実機fixtureで確認する。Phase 2の統計はUsageEventsから上記規約で生成した「記録された利用区間」であり、OS設定画面の集計値との完全一致を保証しない。差が出た場合はevent列と規約を記録し、無根拠な補正値を加えない。

## 7. Sync API契約

### 7.1 Endpointとrequest

```http
POST /api/v1/sync/app-sessions
Content-Type: application/json
```

```json
{
  "schemaVersion": 1,
  "device": {
    "id": "01K4N6Q2N6N8YJ7W4M2D3A9B5C",
    "name": "Google Pixel 9",
    "platform": "android"
  },
  "apps": [
    {
      "id": "01K4N6R7KQJ8J2W9VQW4B6M0TN",
      "identifier": "com.android.chrome",
      "displayName": "Chrome"
    }
  ],
  "sessions": [
    {
      "id": "01K4N70E3Q6N9D6E6G0C8M2H1P",
      "appId": "01K4N6R7KQJ8J2W9VQW4B6M0TN",
      "startedAtMs": 1788908400000,
      "endedAtMs": 1788909600000,
      "durationMs": 1200000,
      "source": "android_usage_stats"
    }
  ]
}
```

契約:

- `extra`フィールドを拒否するversion 1の専用Pydantic schemaを作る。
- 1 requestは0〜100 Session。Androidは空batchを通常送信しないが、APIは空配列へ200と空ACKを返す。
- Sessionが参照するappは同じrequestの`apps`に必ず1件含め、batchを自己完結させる。
- device / app / Session IDはPhase 1と同じ大文字ULID、文字列長、platform、時刻、duration規約で検証する。
- `device.platform`は`android`、`source`はこのCollectorの`android_usage_stats`に限定する。このsource固有検証はSync Adapterで行い、汎用Repositoryへ許可リストを追加しない。
- `apps.identifier`はpackage name、`displayName`は1〜255文字。appのplatformはdeviceから`android`として補う。
- `created_at_ms`と`last_seen_at_ms`はserver受信時刻を使う。Androidの値をPCの保存時刻として信用しない。
- appsの自然キー解決後、Sessionの`app_id`はPC側canonical IDへ差し替えて既存`NormalizedRepository`へ渡す。
- request内の重複ID・矛盾、未参照 / 不足app、別内容で既存IDを再利用する競合は明示的に拒否する。

### 7.2 Responseとatomicity

成功:

```json
{
  "schemaVersion": 1,
  "accepted": ["01K4N70E3Q6N9D6E6G0C8M2H1P"]
}
```

- 1 requestのMaster / Factを1 transactionで保存し、全件成功時だけcommitする。
- 新規保存と、同じID・同じ内容の再送をどちらも`accepted`へrequest順で含める。
- 1件でもvalidation errorまたはcontent conflictならbatch全体をrollbackし、acceptedの部分成功を返さない。
- 同じ自然キーのappが別Android端末またはPhase 1 seedに存在する場合は既存Masterを再利用できる。
- 同じSession IDが別端末または別内容ですでに存在する場合は409とし、既存データを変更しない。
- Androidはresponseの`accepted`をrequested ID集合と照合する。要求していないIDはprotocol error、欠けたIDはpending維持とし、acceptedに存在するIDだけを1 transactionでsyncedへ変える。

### 7.3 Error

既存の共通error形式を維持する。

```json
{
  "error": {
    "code": "sync_conflict",
    "message": "An app session ID is already stored with different content.",
    "field": "sessions[0].id"
  }
}
```

| 状態                                    | HTTP         | code                      | Androidの扱い                                   |
| --------------------------------------- | ------------ | ------------------------- | ----------------------------------------------- |
| JSON・schema・時刻・参照不正、100件超過 | 422          | `invalid_request`         | 全件pending維持。修正不能として詳細を表示       |
| 既存Master / Sessionとの内容競合        | 409          | `sync_conflict`           | 全件pending維持。自動再送せず詳細を表示         |
| SQLite busyが既定timeoutを超過          | 503          | `temporarily_unavailable` | pending維持。ユーザーによる再試行可             |
| 予期しないserver error                  | 500          | `internal_error`          | pending維持。内部情報は表示・responseへ出さない |
| DNS、TLS、Tailscale、timeout、PC停止    | HTTP応答なし | client error分類          | pending維持。接続確認手順を表示                 |

AndroidログとUIへpayload全体、閲覧アプリ一覧、Tailscale identity headerを出さない。エラー表示にはHTTP status、safeなmessage、発生時刻までを使う。

### 7.4 SQLite同時利用

同期書込とTimeline読込が同時に発生するため、Phase 2でPC SQLiteをWAL modeにし、既存のforeign keyとbusy timeout設定を維持する。接続初期化testでpragmaを確認し、次を実測する。

- Timelineの読取中に100件batchを保存できる。
- 同期transaction中も既存のGETが不整合な途中状態を返さない。
- lock timeout時はrollbackされ、503となり、Androidはpendingを維持する。
- Backend再起動後もWAL設定と保存データを利用できる。

WAL関連ファイルは既存どおりGit管理しない。Backupとの整合はPhase 7でSQLite backup API等を使って設計する。

## 8. Androidの手動同期フローとUI

### 8.1 操作フロー

```text
起動
  ├ Usage Accessなし → 説明 + [設定を開く]
  ├ PC URLなし       → HTTPS URL入力 + 保存
  └ 準備完了
       ↓ [収集して同期]
     権限を再確認
       ↓
     UsageEvents収集 → Room transaction
       ↓
     pendingを100件ずつ古い順に送信
       ↓ 各batchのACK
     acceptedだけsynced
       ↓
     pendingが0になるまで逐次実行
       ↓
     結果表示
```

- 二重tap、画面回転、再composeで同期を重複開始しない。process内mutexとViewModel stateで1本に制限する。
- 画面を離れる、processが終了する、通信が切れる場合もRoomのpendingを削除しない。
- 100件を超える場合は複数batchを逐次送信し、成功済みbatchだけsyncedとなる。後続batchの失敗は残件pendingとして表示する。
- 明示的な無制限自動retryはしない。ユーザーが再度押すと同じIDで続きから送る。
- HTTP connect / read / write timeoutを有限値にし、実装時に採用値をテストとREADMEへ記録する。

### 8.2 最小画面

```text
life-timeline

Usage access    許可済み / 要設定
PC endpoint     https://my-pc.example.ts.net
Last collection 2026-09-09 18:30
Last sync       2026-09-09 18:32
Pending         12 sessions

[利用状況へのアクセス設定]
[収集して同期]

Status: 成功 / 収集中 / 同期中 1 of 3 / 接続失敗
```

- 「Connected」は常時接続を監視していると誤解させるため、Phase 2では最後の操作結果を表示する。
- 権限の目的、収集対象、PCへ送る項目、Roomへ保持することを操作前に説明する。
- pending 0とerrorを区別し、失敗時に0件へ見せない。
- PC URLは画面から変更できるが、変更時に既存pendingを削除・syncedへ変更しない。
- TalkBack用label、キーボード / switch access、loading中のbutton disable、長いURLとerrorの折返しを確認する。

## 9. Tailscaleとセキュリティ

### 9.1 Windows側

1. FastAPIを既存どおり`127.0.0.1:8000`で起動する。`0.0.0.0`へ変更しない。
2. Windows PCをtailnetへ参加させ、MagicDNSとHTTPS certificateを利用できることを確認する。
3. PCのlocal serviceをbackgroundのServeで公開する。

   ```powershell
   tailscale serve --bg 8000
   tailscale serve status
   ```

4. 表示された`https://<machine>.<tailnet>.ts.net`をAndroidへ設定する。
5. tailnet ACLで、利用者またはAndroid端末から対象PCのHTTPSだけを許可する。Funnelは使わない。

Serve CLIはversionによりsyntaxが変わり得るため、README更新時にはインストール済みTailscaleの`tailscale serve --help`と公式手順を確認し、実際に成功したコマンドを記録する。

### 9.2 Android側とtrust boundary

- Manifestへ`android.permission.INTERNET`を追加する。
- release相当の設定ではcleartext HTTPを許可しない。自己署名証明書のtrust bypassや全証明書許可を実装しない。
- AndroidとPCは同じtailnetへ参加し、PC URLはTailscale Serveが提示したHTTPS hostnameを使う。
- Phase 2はsingle-user / private tailnetを前提とし、独自passwordやbearer tokenを追加しない。
- Tailscale ACLが接続主体を制限し、ServeがTLSを終端する。APIをLAN / internetへ直接公開しない。
- Tailscale停止、ACL拒否、Serve未設定、FastAPI停止、PC sleepを別の接続失敗として手順で切り分ける。

## 10. 実装タスクと依存関係

各IDを1 Pull Request程度に保ち、必要なら親Issue `Phase 2: Android App Usage MVP`の子Issueにする。API contract変更をAndroidとBackendで別々に先行させない。

```text
P2-01 契約・依存・Android基盤
   ├─→ P2-02 Room・identity・同期状態 ─┐
   ├─→ P2-03 Sync API ─────────────────┤
   └─→ P2-04 UsageStats権限・Adapter ──┤
                                        ↓
                              P2-05 Sessionizer・収集transaction
                                        ↓
                              P2-06 手動sync client・Compose UI
                                        ↓
                              P2-07 Tailscale実通信
                                        ↓
                              P2-08 CI・統合E2E
                                        ↓
                              P2-09 README・実機受け入れ
```

### P2-01: 同期契約とAndroid機能基盤を追加する

- **目的:** AndroidとBackendが同じversion 1契約を実装できる土台を作る。
- **作業:** §7のrequest / response / errorをfixture JSONとして共有し、Room、KSP、Coroutines、Lifecycle、DataStore、Retrofit、OkHttp、serialization、test依存をversion catalogへ固定する。Application-level containerとpackage構成を作る。
- **成果物:** 契約fixture、依存設定、最小domain / DTO、既存placeholderを壊さない構成。
- **完了条件:** Androidのformat・lint・unit test・APK buildと既存全CIが成功し、未使用のWorkManager等を追加していない。

### P2-02: Roomとstable identityを実装する

- **目的:** 同期前のSessionと再処理照合情報を端末再起動後も保持する。
- **依存:** P2-01。
- **作業:** §5のRoom schema / DAO / transaction、schema export、DataStoreのdevice ID・PC URL、ULID生成・検証を実装する。
- **成果物:** Room DB version 1、DAO、Preferences、schema JSON、Room instrumentation test。
- **完了条件:** app / Session / cursor / open stateをatomicに保存でき、pending取得・ACK更新・再open・同一source keyの再投入で件数とIDが変わらない。

### P2-03: Backend Sync APIを実装する

- **目的:** Androidの正規化AppSessionを既存PC Coreへ安全に取り込む。
- **依存:** P2-01。P2-02 / 04と並行可能。
- **作業:** 専用Pydantic schema、Adapter / service、route、batch transaction、ACK、409 / 503 error、WAL設定を追加する。既存`NormalizedRepository`の汎用性を維持する。
- **成果物:** `POST /api/v1/sync/app-sessions`、API / DB test、OpenAPI contract。
- **完了条件:** 新規・同一再送・同一自然キー解決・競合rollback・100件・空batch・同時GETを契約どおり処理し、既存Timeline / Statisticsに反映される。

### P2-04: Usage AccessとUsageEvents Adapterを実装する

- **目的:** OS固有APIから再現可能なdomain event列を得る。
- **依存:** P2-01。
- **作業:** Manifest、権限判定、Settings intent、API level別event mapping、query境界、package label fallback、self package除外、safe diagnosticsを実装する。
- **成果物:** `UsageAccessChecker`、`UsageEventsCollector`、fake可能なAdapter interface、権限UIの最小導線。
- **完了条件:** 未許可時に収集せず案内でき、API 26〜28 / 29〜36のmappingとnull / 空eventをtestできる。

### P2-05: Sessionizerと収集transactionを実装する

- **目的:** event再読込やprocess中断でも確定Sessionを増殖・欠落させない。
- **依存:** P2-02、P2-04。
- **作業:** §6のpure Sessionizer、open state、cursor tie-break、source key、ULID一回生成、Room transaction、collection resultを実装する。
- **成果物:** Sessionizer、CollectionRepository、境界fixture、unit / Room test。
- **完了条件:** 通常切替、複数Activity、同時timestamp、screen off、shutdown / startup、未完了区間、再処理、process failureを規約どおり処理する。

### P2-06: 手動同期clientとCompose画面を完成する

- **目的:** ユーザー操作だけで収集・送信・ACK反映・再試行を行えるようにする。
- **依存:** P2-02、P2-03、P2-05。
- **作業:** Sync API client、100件batch、ACK集合検証、error分類、mutex、ViewModel、§8の画面とURL設定を実装する。
- **成果物:** `SyncRepository`、Main screen、UI state、HTTP / ViewModel / Compose test。
- **完了条件:** 複数batch、部分まで成功後の失敗、同一batch再送、不正ACK、回転、二重tap、PC停止でpendingを失わず、成功ACKだけsyncedになる。

### P2-07: Tailscale Serve経路を成立させる

- **目的:** FastAPIをLAN公開せずAndroid実機からHTTPSで到達させる。
- **依存:** P2-03、P2-06。
- **作業:** §9のServe / ACL / Android設定を専用一時DBで実施し、healthとSync APIを確認する。PC sleep、Serve停止、Tailscale停止からの手動復旧を試す。
- **成果物:** 実測したWindowsコマンド、endpoint、接続・失敗・復旧記録。hostnameや個人tailnet名は公開文書でplaceholder化する。
- **完了条件:** `127.0.0.1` bindを維持し、同じtailnetの許可済みAndroidだけからHTTPS同期でき、Funnel / LAN公開を使っていない。

### P2-08: Android DB・Sync統合と実DB E2EをCI gateにする

- **目的:** Android local stateとPC保存・既存表示の回帰をPRで検出する。
- **依存:** P2-03、P2-05、P2-06。
- **作業:** emulator上のRoom / DataStore testを新しい固定check `android-instrumentation-ci`として追加し、既存`pc-core-e2e`へSync POST→Timeline / Dashboard確認を追加する。rulesetで新checkをrequiredにする。
- **成果物:** Android instrumentation workflow、拡張E2E、diagnostic artifact、ruleset記録。
- **完了条件:** test 0件・skip・`continue-on-error`を成功扱いせず、既存5 checksと新checkが全PRで必須になり、意図的失敗時にmergeできない。

### P2-09: Windows / Android実機で受け入れ、文書を更新する

- **目的:** 新しいセットアップから実機の履歴がPCへ届くことを再現できるようにする。
- **依存:** P2-07、P2-08。
- **作業:** READMEへ依存導入、Usage Access、PC URL、Tailscale Serve、手動同期、失敗の切り分け、データ確認を追記する。§12を実施して`docs/development/phase2-acceptance.md`へ結果を残す。上位文書の旧API例・Phase状態も必要な範囲で更新する。
- **成果物:** 実行手順、Phase 2受け入れ記録、Phase 3への実測値と既知課題。
- **完了条件:** AC-01〜15を実機で満たし、第三者が秘密値なしの手順を読んで同じ構成を再現できる。

## 11. テスト・CI計画

### 11.1 Android unit test

OS objectへ依存しないfixtureを使い、少なくとも次を検証する。

- API 26〜28と29〜36のforeground / background mapping。
- 同じpackageの単純な開始・終了、アプリ切替、同一package内Activity切替。
- 重複resume / pause、endだけ、同じtimestamp、逆転、空package。
- screen off、shutdown / startup、cursorを跨ぐopen Session。
- 日跨ぎSessionを分割せず、durationを整数msで保持すること。
- 同じevent列 / source keyを再処理して同じRoom IDを使うこと。
- Package label取得不能時のidentifier fallbackとself package除外。
- batchを古い順に100件へ分ける処理。
- acceptedが全件、部分、空、未知ID、重複IDの場合のRoom更新。
- 422 / 409 / 500 / 503、timeout、DNS、TLS failureの分類とpending維持。
- device IDとPC URLのvalidation、再起動相当の再read。
- ViewModelの二重tap、回転相当、成功・空・error state。

### 11.2 Android instrumentation / Room test

- version 1 DB作成、全table / index / constraint、schema JSON。
- app自然キーupsert、Session insert、pending順序、100件limit、accepted IDsだけの一括更新。
- app / Session / cursor / open stateが同じtransactionでrollbackされること。
- DB close / reopenとprocess再作成相当でもpending・device ID・URLが残ること。
- 同一source key・同内容の再投入が元のIDとsync状態を維持し、異内容を拒否すること。
- migration test harnessを初版から動かし、将来versionを追加できること。
- Composeで権限未許可、準備完了、同期中、成功、接続失敗、pending残件を描画・操作できること。

実際のUsage Access許可とOEMのUsageEvents内容はinstrumentationだけで保証せず、§12の実機testを必須とする。

### 11.3 Backend API / DB test

- 正常な1件、100件、空batch。
- 同一payload再送で件数・`created_at_ms`・Timeline統計が変わらない。
- 2端末の同じpackageが1つのPC app Masterを共有し、Sessionは端末別に残る。
- Androidから別app IDで既存自然キーを送ってcanonical appを再利用する。
- device / app / Session ID、文字列、platform、source、duration、参照、duplicate listの不正。
- 同じSession IDの別端末・別内容、app IDの別自然キー競合。
- batch途中のvalidation / DB errorでMasterを含め全rollback。
- 既存Timeline itemのID・元区間・`display`、Statistics合計との一致。
- 同時再送、GETとPOSTの並行、WAL / busy timeout / 503。
- error responseへDB path、SQL、stack trace、payloadを含めない。

### 11.4 結合・E2E

既存`pc-core-e2e`の一時SQLiteへmigrationを適用し、Frontendを固定dataでmockせず次を追加する。

```text
実Backend / Frontend起動
  → Sync APIへAndroid version 1 fixtureをPOST
  → accepted IDsを確認
  → 同じpayloadを再POSTし同じaccepted IDsを確認
  → React TimelineでAndroid app / device / 時刻 / durationを確認
  → Dashboardで追加分の件数・時間を確認
  → 日付切替と日跨ぎ表示を確認
  → DB件数が再送前後で同じことを確認
  → 競合batchが409で全rollbackされることを確認
```

これはAndroid OS / Room / Tailscaleの代替ではない。Android側はMockWebServer + Roomの統合test、最終経路は実機受け入れで補完する。

### 11.5 CI gate

- 既存`frontend-ci`、`backend-ci (ubuntu)`、`backend-ci (windows)`、`android-ci`、`pc-core-e2e`を維持する。
- `android-ci`でformat、Android Lint、unit test、debug APK buildを続ける。
- emulatorのRoom / Compose instrumentationを`android-instrumentation-ci`として全PRで実行する。
- `pc-core-e2e`をSync POSTから既存React表示まで拡張し、docs-only PRでも省略しない。
- Android / E2E失敗時はJUnit、logcat、Playwright trace、Backend logをartifact化する。収集アプリ名や実tailnet情報をCI artifactへ入れない。
- Tailscale用の長期秘密情報をGitHub Actionsへ追加しない。実tailnet E2Eはローカル受け入れとし、CI成功だけでAC-13〜15をPASSにしない。

## 12. 実機受け入れ手順と完了条件

### 12.1 専用環境

1. 新しいcheckoutまたはcleanな作業treeから、READMEどおりBackend / Frontend / Androidをbuildする。
2. `%TEMP%`等のPhase 2専用絶対パスを`LIFE_TIMELINE_DATA_DIR`へ設定し、migrationを適用する。
3. FastAPIを`127.0.0.1:8000`、Frontendを`127.0.0.1:5173`で起動する。
4. WindowsとAndroidを同じtailnetへ接続し、Funnelを使わずServeとACLを設定する。
5. debug APKを実機へinstallし、PCのTailscale HTTPS URLを設定する。

### 12.2 代表シナリオ

1. Usage Access未許可でアプリを開き、理由と設定導線が表示され、収集されないことを確認する。
2. 設定で許可し、life-timelineへ戻ると許可済みに変わることを確認する。
3. Chrome等の識別可能なアプリを開始・終了し、別アプリへの切替を含む2件以上の確定区間を作る。
4. PC Backendを停止したまま「収集して同期」を押し、SessionがRoomへpending保存され接続失敗後も残ることを確認する。
5. Androidアプリと端末を再起動し、pending件数とstable device IDが変わらないことを確認する。
6. Backendを起動して再度同期し、全件のACKとpending 0を確認する。
7. 同じ日付 / timezoneのReact TimelineとDashboardを開き、実機のpackage / label、端末名、区間、時間、件数を照合する。
8. DBの件数を記録し、同じevent範囲を再収集・再同期して件数と既存Session IDが変わらないことを確認する。
9. TailscaleまたはServeを停止して新しいSessionを収集し、pending維持を確認する。復旧後の手動同期で追加分だけ保存されることを確認する。
10. 100件超のtest fixtureは個人利用eventを作らず自動testで確認し、複数batchの途中失敗・再開を検証する。

### 12.3 受け入れチェックリスト

| ID    | 完了条件                                                                                       | 主な証拠                                   |
| ----- | ---------------------------------------------------------------------------------------------- | ------------------------------------------ |
| AC-01 | Usage Accessの未許可・許可済みを正しく判定し、Settingsへ安全に案内できる                       | 実機画面、Adapter / UI test                |
| AC-02 | API 26〜36のeventを規約どおりAppSession化し、終了未確定区間を送らない                          | Sessionizer unit test、対象実機記録        |
| AC-03 | AppSessionへULIDを一度だけ付け、Room再open・再処理で同じIDを維持する                           | Room / source key / process再作成test      |
| AC-04 | PC停止・通信失敗・Android再起動後もRoomのpending Sessionを失わない                             | 実機停止・再起動シナリオ                   |
| AC-05 | device IDとapp IDが安定し、package name・label fallback・UTC epoch ms・durationが契約どおり    | Android unit / Room test、Sync request記録 |
| AC-06 | 手動操作でpendingを100件ずつ古い順に送り、accepted IDだけsyncedへ変更する                      | MockWebServer / Room統合test               |
| AC-07 | Sync APIがbatchをatomicに保存し、新規と同一再送へ同じACKを返す                                 | Backend API / DB test                      |
| AC-08 | 同じpayloadを再送してもPCのMaster / Fact件数と`created_at_ms`が変わらない                      | Backend test、実機再送確認                 |
| AC-09 | 競合・不正・途中失敗で既存記録を上書きせず、batch全体をrollbackする                            | 422 / 409 / 500 / 503 test                 |
| AC-10 | 2端末の同じAndroid packageを1つのPC app Masterへ正規化できる                                   | 自然キー解決test                           |
| AC-11 | 同期済み実機Sessionが既存TimelineとDashboardへ同じID・当日分時間で表示される                   | `pc-core-e2e`、React実機確認               |
| AC-12 | Android画面で権限、endpoint、最終収集、最終同期、pending、safeなerrorを区別できる              | Compose / ViewModel test、実機画面         |
| AC-13 | FastAPIをloopbackに保ち、Tailscale Serve HTTPSとACL経由だけでAndroidから同期できる             | bind / Serve status / ACL / HTTPS確認      |
| AC-14 | PC / Tailscale / Serve停止時にpendingを維持し、復旧後の手動再送で二重登録しない                | 実機障害・復旧記録                         |
| AC-15 | 既存5 checksと`android-instrumentation-ci`がrequiredで成功し、READMEだけで受け入れを再現できる | CI / ruleset、Phase 2受け入れ記録          |

AC-01〜15をすべて実施し、実機由来SessionがTailscale経由でPC Timelineへ表示された時点でPhase 2を完了とする。CI上のfixtureだけ、LANへの直接公開、ADBでDBへ直接投入、Android画面上の表示だけでは完成条件を満たさない。

## 13. リスクとPhase 3への引き継ぎ

| リスク・論点                                  | Phase 2での対処                                         | Phase 3以降への引き継ぎ                                            |
| --------------------------------------------- | ------------------------------------------------------- | ------------------------------------------------------------------ |
| UsageEventsはOSに数日しか残らない             | 起動 / 手動同期時に収集し、初回取得範囲と限界を表示する | WorkManagerで定期収集し、長期間アプリを開かなくてもRoomへ退避する  |
| foreground / background eventが欠落・重複する | open state、cursor、決定順、異常diagnostic、実機fixture | OEM / multi-window実測に基づき規約をversion化する                  |
| 再queryで別ULIDを生成する                     | Room transactionと一意`source_key`で既存IDを再利用      | Collector algorithm変更時の再処理 / migration方針を定義する        |
| ACK未受信だがPC保存済み                       | accepted前はpendingのまま保持し、同じIDで再送する       | WorkManager retryも同じRepositoryとIDを利用する                    |
| batch途中の一部保存で状態が曖昧になる         | server側を1 request 1 transactionにする                 | 種類横断batchを追加する場合もACK単位を明示する                     |
| app labelや端末名が変わる                     | 自然キーはpackage / device ID、表示名だけ更新可能にする | Device / app管理と名称履歴はPhase 7で検討する                      |
| SQLite readとsync writeが競合する             | WAL、短い100件transaction、busy timeout、503            | 自動同期頻度に応じてbatch size / backoffを実測調整する             |
| PC URLやTailscale状態の誤設定                 | HTTPS URL検証と具体的な切り分け表示                     | 接続設定・診断画面をPhase 7で拡充する                              |
| tailnet内の不要な主体がAPIへ到達する          | ACLを最小化し、Funnel / LAN公開を禁止する               | 複数ユーザー・端末を扱う場合にidentity header等を再検討する        |
| manual sync中にprocessが終了する              | 永続`syncing`を使わず、ACK前はpending                   | WorkManager導入時はlease、stale syncing復旧、unique workを設計する |
| Room schema変更で履歴を消す                   | schema export、migration test、破壊的fallback禁止       | 全Android Phaseでmigration chainと過去schemaを維持する             |

Phase 3は、Phase 2の`CollectionRepository`、`SyncRepository`、Room ID / pending状態、version 1 APIを置き換えずにWorkManagerから呼び出す。unique work、実行中lease、network constraint、指数backoff、再起動復旧、PC長期停止、battery制約を追加し、手動Syncは診断・即時実行手段として残す。

## 14. 実装時に更新する文書

- `README.md`: Androidセットアップ、Usage Access、Tailscale Serve、手動同期、確認値、トラブルシュート。
- `docs/architecture.md`: Phase 2時点の手動Sync経路と、WorkManagerがPhase 3であること。
- `docs/technical-design.md`: version 1 Sync API、単独主キー、source key、ACK / error / transaction契約。
- `docs/data-model.md`: Android Roomの実table・状態・cursorと、PC canonical app解決。
- `docs/implementation-plan.md`: Phase 2の完了状態とPhase 3開始条件。
- `docs/development/toolchains.md`: 新規Android dependency / pluginを文書で管理する必要がある場合の採用値。
- `docs/development/phase2-acceptance.md`: 実機、Tailscale、CI、AC-01〜15の実測結果。

## 15. 一次資料

- [Android UsageStatsManager API](https://developer.android.com/reference/android/app/usage/UsageStatsManager)
- [Android UsageEvents.Event API](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [Room database testing](https://developer.android.com/training/data-storage/room/testing-db)
- [Room migration and schema export](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)
- [tailscale serve command](https://tailscale.com/docs/reference/tailscale-cli/serve)

# life-timeline 技術設計

## 1. 設計方針

life-timelineでは以下を重視します。

- データを失わない
- 同じデータを何度送っても壊れない
- 各データソースを独立させる
- 生データと表示用データを分離する
- クラウドに依存しない
- セキュリティプロトコルを自作しない
- 長期間運用できるデータ形式にする
- 段階的に機能追加できる構造にする

---

## 2. データモデル

### 基本方針

Timelineはlife-timelineの中心的な表示機能ですが、**DBにはTimeline Itemそのものを原本として保存しません**。

保存層では、各データを意味に応じて正規化して保持します。

```text
Collectors
   ↓
Normalized Data
   ├── app_sessions
   ├── location_points
   ├── place_visits
   ├── media_items
   └── manual_records
          ↓
Presentation / Analysis
   ├── Timeline
   ├── Statistics
   ├── Map
   ├── Photos
   └── Search
```

これにより、同じ記録をTimeline以外の統計・地図・一覧・検索にも再利用できます。

詳細なデータモデルは `data-model.md` に定義します。

### Dimension / Master

- `devices`: 端末情報
- `apps`: Android / Windows共通のアプリ情報
- `categories`: アプリ等の統計カテゴリ
- `places`: 自宅・大学・駅など意味のある場所

### Fact / Record

- `app_sessions`: Android / Windows共通のアプリ利用セッション
- `desktop_session_details`: PC固有のwindow title / URL等
- `location_points`: 位置情報の生データ
- `place_visits`: LocationPointから生成した滞在記録
- `media_items`: 写真・動画の記録
- `manual_records`: 手動で追加する記録

### Aggregate / Cache

統計表示の高速化が必要になった場合、以下のような再生成可能な集計テーブルを追加します。

- `daily_app_stats`
- `daily_device_stats`
- `daily_location_stats`
- `daily_media_stats`

```text
Normalized Data = Source of Truth
Aggregate Data  = Rebuildable Cache
```

集計ロジックを変更した場合は、正規化データから再生成できることを前提とします。

---

## 3. 時刻

DB内部では原則としてUTCで保存します。

```text
2026-09-03T06:00:00Z
```

UI表示時にローカルタイムへ変換します。

必要に応じてイベント発生時のtimezoneも保存します。

目的:

- 海外利用への対応
- Android / Windows間の時刻統一
- DST対応
- 異なるソースの時刻比較

---

## 4. ID

データIDにはUUID v7またはULIDを使用します。

Android側でIDを生成します。

```text
Android
  ↓
ID生成
  ↓
Room
  ↓
PCへ送信
```

PC側では以下を一意とします。

```text
device_id + event_id
```

同じデータを再送しても重複登録されないようにします。

---

## 5. 同期設計

### 基本原則

`last_sync_at` のみを基準にしません。

各レコードについて、

```text
pending
synced
```

を管理します。

```text
収集
 ↓
Roomへ保存
 ↓
pending
 ↓
PCへ送信
 ↓
PCが保存
 ↓
accepted ID返却
 ↓
Android側でsynced
```

### バッチ同期

例:

```text
app_sessions: 100件
locations: 200件
photos: 20件
```

など、データ種類ごとに適切なサイズでまとめて送信します。

### API例

```http
POST /api/v1/sync/app-sessions
POST /api/v1/sync/locations
POST /api/v1/sync/photos
```

レスポンス:

```json
{
  "accepted": [
    "01991...",
    "01992..."
  ]
}
```

ACKを受け取ったものだけAndroid側で同期済みにします。

### リトライ

PCが以下の状態でもAndroid側のデータを消しません。

- 電源OFF
- スリープ
- FastAPI停止
- Tailscale未接続
- 通信切断

Phase 3のAppSession workerはWorkManagerの定期収集・自動retry・network constraint・指数backoffを担当します。写真には独立したphoto collection / photo sync worker、unique work name、Room leaseを使い、AppSession workerを置き換えません。未ACKの記録はpendingを維持し、復旧後に同じIDで再送します。画面の手動操作は診断・即時実行の入口として残します。

---

## 6. 写真設計

### 収集範囲とpermission

- MediaStoreに公開済みで`DCIM/`配下にある画像を対象にする。DCIM外、Secure Folder、別Android userの画像は対象外になり得る。`DCIM/`はCamera撮影の保証ではなく、DCIM内の非Camera画像が含まれる場合もある。
- 収集はユーザーのopt-inとOS permissionの両方が必要。Android 14以降のfull / selected partial / deniedを実行時に判定し、permissionだけでopt-inとみなさない。
- full accessでは有効化時baseline以降に追加された画像を収集し、過去library全体はimportしない。partial accessでは選択済み画像を対象とし、未選択の将来画像は保証しない。
- API 30以降はvolumeのMediaStore versionと`GENERATION_MODIFIED`で差分scanし、API 26〜29は`DATE_ADDED` / `_ID` cursorを使う。`IS_PENDING=0`を適用する。
- EXIF位置は別permissionで取得可能な場合だけnullable metadataとして扱う。permission拒否でも写真収集・同期を継続する。

### 保存するmetadataとthumbnail

MediaItemにsource ID、撮影時刻、filename、original MIME type、width / height、任意のEXIF緯度・経度を保存する。原本path、URI、byte列は永続化しない。

```text
最大辺: 512px（拡大なし）
形式: WebP lossy
quality: 65
thumbnail上限: 1 MiB / file
```

Androidは端末内private directoryにthumbnailをatomic保存し、ACK後に一時fileを削除する。PCはmetadataをSQLiteの`media_items`へ、WebPだけを`thumbnails/`へ保存する。Androidの原本削除はPCの記録・thumbnailへ伝播しない。thumbnail生成前に原本へアクセスできなくなった場合はmetadata-only記録となり得る。

### Workerと同期上限

| 対象 | 固定値 |
| --- | --- |
| periodic collection work | `life_timeline_photo_collection_v1` |
| one-time collection work | `life_timeline_photo_collection_now_v1` |
| photo sync work | `life_timeline_photo_sync_v1` |
| Room lease | `photo_collection_v1` / `photo_sync_v1` |
| collection周期 / flex | 15分 / 5分（開始時刻の保証なし） |
| collection constraints | `BatteryNotLow`、`StorageNotLow` |
| sync constraints | `UNMETERED`、`BatteryNotLow`、`StorageNotLow` |
| scan上限 | 1 run 2,000 raw rows、最大200 discoveries |
| thumbnail生成 | 1 run 最大20件 |
| multipart batch / request | 最大20 photos / 20 MiB |
| 1 run上限 | 8分または10 batch |
| backoff / lease TTL | exponential初期30分 / 15分 |

1件のthumbnailは最大1 MiB、辺の最大値は512px。WorkerはACKされたIDのみsyncedにし、未ACKをpendingに保つ。写真APIは`POST /api/v1/sync/photos`、日別表示は`GET /api/v1/photos?date=...&timezone=...`、thumbnail配信は`GET /api/v1/media/{id}/thumbnail`を使う。Uploadの総量と時間には上限を設け、OS制約によりbackground処理の開始・完了が遅れることを前提とする。

## 7. 位置情報設計

位置情報はAndroid側で唯一、継続的な取得が必要になるデータです。

初期設定候補:

- 5分程度の周期
- または一定距離移動時
- 高精度GPSを常時要求しない

実機テストでバッテリー消費と精度を比較し、最終値を決定します。

PC側では生のLocationPointを保存し、後からPlaceVisitを生成します。

例:

```text
12:01 point
12:03 point
12:07 point
12:10 point

↓ クラスタリング

12:01〜12:10
東京駅周辺
```

---

## 8. ActivityWatch連携

ActivityWatchをPC側のcollectorとして扱います。

life-timeline側はREST API経由でデータを取得します。

```text
ActivityWatch
   ↓
Adapter
   ↓
desktop_sessions
```

ActivityWatchの内部データ構造にlife-timeline本体を依存させず、Adapterで変換します。

---

## 9. Presentation / Query API

DBには万能Timelineテーブルを作らず、データ種類ごとに保存します。

Timelineは中心機能ですが、DB上の正規化データから表示時に組み立てます。

```http
GET /api/v1/timeline?date=2026-09-03
```

例:

```json
[
  {
    "type": "desktop_session",
    "startedAt": "...",
    "app": "VS Code"
  },
  {
    "type": "place_visit",
    "startedAt": "...",
    "label": "東京駅"
  },
  {
    "type": "photo",
    "takenAt": "...",
    "thumbnailUrl": "..."
  }
]
```

FrontendではDiscriminated Unionとして扱います。

同じ原本データから、統計・地図・写真一覧などのAPIも構築します。Timelineは「時系列で見るためのView」であり、保存形式そのものではありません。

---

## 10. セキュリティ

### 通信

- Tailscaleを利用
- FastAPIを `127.0.0.1` のみにbind
- Tailscale Serve経由でAndroidからアクセス
- APIを直接LAN公開しない
- 独自TLSを実装しない
- 独自暗号方式を作らない

### 保存

PC:

- Windowsユーザー認証
- BitLocker等のOS暗号化を利用

Android:

- Android標準の端末暗号化
- 必要な秘密情報はAndroid Keystore

独自のファイル暗号化はMVPでは行いません。

---

## 11. バックアップ

写真を含むPCデータの一つの完全な単位は、data root全体、特に次の二つの組み合わせです。

```text
LIFE_TIMELINE_DATA_DIR/
├── lifelog.db
└── thumbnails/
```

DB metadataとthumbnail fileを別々の時点や別snapshotから復元すると、thumbnail欠損または孤立fileが発生します。現時点では自動backup / restore UIを実装していません。運用上の手動snapshotではFastAPIを停止してdata root全体をコピーし、復旧も同一snapshotのdata root全体を置き換えます。具体的なPowerShell手順はREADMEの[手動バックアップと復旧](../README.md#手動バックアップと復旧)を参照してください。

将来のExport / Backup機能では、DBと関連fileの一貫性を保つsnapshot手順を実装します。候補formatはJSON、CSV、GeoJSONとthumbnail files。

# 12. テスト方針

## Backend

### Unit Test

pytestを使用します。

対象:

- Session生成
- Timeline統合
- PlaceVisit生成
- UTC変換
- ActivityWatch変換
- 重複データ処理

### API Test

FastAPI TestClient / httpxを使用します。

対象:

```text
POST /sync/*
GET /timeline
GET /photos/*
```

特に以下を確認します。

- 正常登録
- 同じIDの再送
- 不正なpayload
- 空配列
- 大量データ
- 部分的な重複

### DB Test

テスト用SQLiteを毎回生成します。

MigrationについてもCIで最新schemaまで適用可能か確認します。

---

## Frontend

### Unit / Component Test

- Vitest
- React Testing Library

対象:

- Timeline Item
- 日付切替
- Filter
- API Response変換
- 空状態
- Error状態

### E2E

Playwrightを使用します。

代表シナリオ:

```text
アプリを開く
↓
日付を選択
↓
Timelineが表示される
↓
写真を開く
↓
Mapへ移動
```

---

## Android

### Unit Test

- JUnit
- Kotlin Coroutines Test

対象:

- UsageStats → AppSession変換
- sync state
- batch生成
- retry判定

### Room Test

in-memory Room DBを利用します。

確認:

- insert
- pending取得
- synced更新
- migration

### Instrumentation Test

実端末またはAndroid Emulatorで実施します。

特にOS API依存部分:

- UsageStats権限
- MediaStore
- WorkManager
- Room
- Runtime Permission

### 実機テスト

位置情報についてはエミュレータだけで判断しません。

確認項目:

- バッテリー消費
- バックグラウンド取得
- Wi-Fi / 5G切替
- 端末再起動
- PCが1日OFFだった場合
- 写真大量同期

---

# 13. CI

GitHub Actionsを使用します。

## Pull Request

PRごとに以下を実行します。

```text
Backend
├ lint
├ type check
├ unit test
└ migration test

Frontend
├ lint
├ type check
├ unit test
└ build

Android
├ lint
├ unit test
├ debug APK build
└ instrumentation test
```

Phase 2以降のPull Requestでは、`pc-core-e2e`と`android-instrumentation-ci`もrequired checkとして実行します。実tailnetを使う受け入れは秘密情報をCIへ持ち込まず、Windows / Android実機のローカル手順と受け入れ記録で確認します。

## 推奨ツール

Backend:

- Ruff
- mypy または pyright
- pytest

Frontend:

- ESLint
- TypeScript
- Vitest
- React Testing Library

Android:

- Android Lint
- ktlint または Spotless
- JUnit
- Gradle

## E2E

`pc-core-e2e`は実DBからSync APIを経由してReact表示まで、`android-instrumentation-ci`はEmulator上のRoom / Compose状態までを毎Pull Requestで確認します。Tailscaleを含む実機経路はローカル受け入れで補完します。

---

# 14. Git運用

## 14.1 Issue・Pull Request・コミット・ブランチの命名規則

命名は、GitHub上で読むIssue / Pull Requestと、Git履歴・checkoutで扱うコミット / ブランチを分けて考える。IssueとPull Requestの内容は日本語、コミットメッセージとブランチ名は英語にする。

| 対象 | Issueに紐づく変更 | Issueに紐づかない変更 |
| --- | --- | --- |
| Issueタイトル | `[P2-03] Android AppSession同期APIを実装する` | 原則として先にIssueを作成する。緊急の小変更などでIssueを作らない場合は作成理由をPRへ記録する |
| Pull Requestタイトル | `[P2-03] Android AppSession同期APIを実装する` | `feat: 管理画面の表示を改善する` のように `feat:` / `fix:` / `docs:` 等を文頭へ付ける |
| コミットメッセージ | `feat: add Android app session sync API` | `feat: add ...`、`fix: correct ...`、`docs: update ...` 等のConventional Commits形式にする |
| ブランチ名 | `feature/32-backend-sync-api` | `feature/refresh-api-docs`、`fix/duplicate-sync`、`docs/update-sync-design` 等、英語のtypeとkebab-caseにする |

Issue / Pull Requestのタイトルでは、`P2-XX`のPhase識別子を先頭に置き、対応内容を日本語で具体的に書く。Pull Request本文には対応Issueを `Closes #番号` または `Fixes #番号` で記載する。コミットとブランチでは、Issue番号を含める場合もtypeと説明を英語にする。

`feat`、`fix`、`docs`、`test`、`refactor`、`chore`を変更の主目的に応じて使い分ける。Issueへ紐づかない変更は、Pull Requestタイトルとコミットメッセージの文頭にこのtypeを必ず付ける。

## 基本方針

**すべての実装タスクは、コードを書き始める前にGitHub Issueを作成します。**

実装は必ず以下の流れで進めます。

```text
1. 実装内容を整理
   ↓
2. GitHub Issueを作成
   ↓
3. Issue番号を含むブランチを作成
   ↓
4. 実装・テスト
   ↓
5. Issue番号を含むPull Requestを作成
   ↓
6. Review / CI
   ↓
7. mainへmerge
   ↓
8. IssueをClose
```

Issueを作成せずに直接実装を始めることは原則として避けます。

Issueには最低限、以下を記載します。

- 目的
- 実装内容
- 完了条件
- 必要に応じて技術的な補足
- 関連Issue / Pull Request

## ブランチ

基本:

```text
main
└── feature/*
```

ブランチ名には対応するIssue番号を含めます。

例:

```text
feature/12-android-usage-stats
feature/18-sync-api
feature/24-timeline-ui
feature/31-photo-sync
feature/42-location-tracking
```

バグ修正の場合:

```text
fix/53-duplicate-sync
```

ドキュメント変更の場合:

```text
docs/61-update-sync-design
```

## Pull Request

すべての変更はPull Request経由でmainへmergeします。

Pull Requestには対応するIssue番号を必ず記載します。

例:

```text
Implement Android usage stats sync

Closes #12
```

GitHubの `Closes #12`、`Fixes #12` などのキーワードを利用し、PRがmainへmergeされた時点で対応Issueが自動的にCloseされるようにします。

PRには最低限、以下を記載します。

- 対応Issue
- 変更内容
- 動作確認内容
- 必要に応じてスクリーンショット
- 未対応事項や既知の問題

## Merge条件

以下を満たしてからmainへmergeします。

- 対応Issueの完了条件を満たしている
- 必要なテストが追加・更新されている
- CIが成功している
- 自分またはレビュアーによる確認が完了している
- PRが対応Issueと紐づいている

mainは常に最低限build可能かつテスト可能な状態を維持します。

## Issueと実装の粒度

Issueは、1つのPull Requestで解決できる程度の大きさを基本とします。

大きな機能については、親Issueを作成し、実装可能な単位へ分割します。

例:

```text
#20 Photo Support

├─ #21 MediaStoreから写真を取得
├─ #22 サムネイル生成
├─ #23 Photo Sync API
└─ #24 Timelineへの写真表示
```

各子Issueごとにブランチ・Pull Requestを作成し、段階的に実装します。

---

# 15. Migration

DB schemaの変更はAlembicで管理します。写真テーブルのmigration IDは`0002_media_items`です。

Android Roomについてもmigrationを明示的に管理します。最終schemaはversion 4です。Room v3で写真entityとMediaStore cursor tableを追加し、v3→v4では既存recordを保持したままphoto generation cursorを再走査します。schema JSONとmigration testもversion 4に揃えています。

ライフログアプリは長期間データを保持するため、「開発中だからDBを消して作り直す」という運用からできるだけ早く脱却します。

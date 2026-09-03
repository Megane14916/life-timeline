# Phase 1 詳細実装計画: PC Core

- 作成日: 2026-09-03
- 対象: Windows PC上での正規化データ保存・日付別Timeline・基本統計表示
- 状態: 実装前の計画
- 完成条件: 同じダミーデータをReactのTimelineと最小Dashboardの基本統計から閲覧し、日付を変更して過去の記録を確認できる

## 1. 参照資料と位置付け

本計画は、以下の6文書を基にPhase 1の作業単位・データ契約・検証方法を具体化する。

| 文書 | Phase 1に関係する方針 | 本計画への反映 |
| --- | --- | --- |
| [overview.md](../overview.md) | PCをデータ管理の中心とし、SQLiteとローカルファイルへ保存する | ローカルDBを正とし、PC単体で保存・表示の経路を成立させる |
| [product-spec.md](../product-spec.md) | Timelineに加え、同じ原本からStatistics等を表示する | 日付切替・利用区間・アプリ別統計の表示、再起動後の永続化、loopback限定のAPI |
| [architecture.md](../architecture.md) | React / TypeScript / Vite、FastAPI、SQLite、Alembic | 既定の技術構成を使い、Frontend → API → DBを接続する |
| [technical-design.md](../technical-design.md) | Normalized Dataを正とし、Presentation / Query APIで用途別に変換する | 保存処理とTimeline・Statisticsの処理を分離し、テスト・CI・Migrationを定義する |
| [data-model.md](../data-model.md) | Dimension / Factの分離、共通AppSession、`*_at_ms`・`duration_ms`、再生成可能なView / Aggregate | `devices`・`categories`・`apps`・`app_sessions`の初期schemaと集計規約を定める |
| [implementation-plan.md](../implementation-plan.md) | Phase 1に正規化モデル・基本Statistics API・最小Dashboardを含む | 同じダミーデータをTimelineと基本統計で閲覧する作業・受け入れ条件へ分解する |

上位文書に選択肢や例だけがある箇所は、本計画でPhase 1の採用案を定める。将来フェーズ全体の仕様を、この段階で確定するものではない。

### 文書間に残る記述差の扱い

- schemaの基準は`data-model.md`とする。`technical-design.md`§4の`device_id + event_id`という重複防止方針に対し、`data-model.md`は`app_sessions.id`単独をPRIMARY KEYとしている。本計画は後者を採用し、同一IDの端末・内容を照合する。同じ端末の再送は増えず、別端末の同一IDは競合として拒否する（§5）。
- `technical-design.md`§8と`implementation-plan.md`のPhase 6に残る`desktop_sessions`は旧名として扱う。保存先は共通の`app_sessions`とPC固有の`desktop_session_details`に読み替え、後者とActivityWatch取込はPhase 6へ残す。
- `technical-design.md`§9の`desktop_session` / `photo`等は表示用レスポンスの例であり、同名テーブルを作る根拠にはしない。後続の保存モデルは`media_items`・`manual_records`を用いる。
- `data-model.md`§15にあるULID / ORM等の未決事項は、Phase 1では§3.2の採用案で進める。Categoryの本番初期値や管理UIは今回確定しない。
- AppSession IDはLifeTimelineが正規化Sessionの生成時に付与するIDとし、OSの生イベントIDとは区別する（§5.3）。日跨ぎ計算はBackendに集約し、E2Eは上位設計の任意実行案にかかわらず全PRの必須チェックとする（§6・§11）。

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
開発用seedコマンド
        ↓
SQLite: devices / categories / apps / app_sessions
        （Normalized Data = Source of Truth）
        ↓
FastAPI: Timeline Query / Statistics Query
        ↓
React: 日付別Timeline / 最小Dashboard
        ↓
日付変更・再読み込み・Backend再起動後も記録を確認
```

最初の表示対象は **Android / Windows共通のAppSessionのダミーデータ** とする。Androidの複数端末とWindowsのfixtureで同じモデルから表示・集計できることを確認し、Phase 2でAndroid同期、Phase 6でActivityWatchの投入経路を追加する。Phase 1の「PC Core」はPC側の保存・表示基盤を指し、PC上で実際のアプリ使用履歴を収集する機能はPhase 6に属する。

### 2.2 実装するもの

- PC側の設定、SQLite接続、トランザクション管理。
- Alembicによる初回migration。
- `devices` / `categories` / `apps` / `app_sessions` の保存・取得処理。
- 再実行可能な開発用ダミーデータ投入コマンド。
- 日付・タイムゾーンに基づくTimeline取得API。
- 同じ正規化データを直接集計する基本Statistics API（アプリ別利用時間・Session件数と合計）。
- ReactのTimeline、日付入力、前日・翌日・今日への移動。
- 同じ日付を共有する最小Dashboard（合計値とアプリ別の表）。
- 読み込み中・記録なし・APIエラーの基本表示。
- 永続化・正規化・日付境界・重複防止・Timelineと統計の一致を中心としたテスト、既存CIへの追加。
- Windowsで再現できる起動・検証手順。

### 2.3 後続フェーズへ残すもの

| 対象 | 実施フェーズ・扱い |
| --- | --- |
| AndroidのRoom / UsageStats / Session生成、同期用POST API、Tailscale接続 | Phase 2 |
| 自動同期、WorkManager、バッチ再送、pending / synced管理 | Phase 3。ただしPhase 2から手動同期に必要なACKと重複防止は扱う |
| 写真とサムネイル、写真一覧、`media_items` | Phase 4。動画収集の実装は別途計画する |
| 位置情報、`places`、PlaceVisit、地図 | Phase 5 |
| ActivityWatch、PCアプリ・ウィンドウ・Web履歴、`desktop_session_details` | Phase 6。共通`app_sessions`へのダミーデータ保存はPhase 1で扱う |
| 月間Calendar、検索・Filter、Device / Category管理画面、`manual_records`、Backup / Export、設定画面 | Phase 7 |
| 本格的なStatistics画面、週次・月次グラフ、カテゴリ別・時間帯別等の分析 | Phase 7。日別の最小DashboardはPhase 1に含む |
| `daily_*_stats`等のAggregate / Cache | 実測で必要になった時点。Phase 1では原本を直接集計する |
| Tauri、常駐サービス、Windows自動起動 | Phase 8 |
| 外部連携、AI要約・分析 | Phase 9以降 |

Phase 7のError UIに先行して、Phase 1ではAPI失敗を記録なしと区別する最低限の表示を実装する。Timelineのitemや統計結果は派生データとして都度生成し、原本テーブルには保存しない。`categories`は`apps.category_id`の参照先として含めるが、未使用の将来Factや集計キャッシュは作成しない。

## 3. 開始条件と技術上の採用案

### 3.1 現状とPhase 0への依存

計画作成時点のリポジトリには、READMEと設計文書があり、Backend / Frontend / Androidのプロジェクト雛形・依存管理・CI設定はまだない。

実装開始時にPhase 0の成果を確認する。

- `backend/`: FastAPIが起動し、Python依存管理・Ruff・型チェック・pytestが動く。
- `frontend/`: React + TypeScript + Viteが起動し、lint・型チェック・buildが動く。
- GitHub ActionsでBackend / Frontendの最低限の検証が成功する。
- 実データ、ローカル設定、DBファイルをGit管理しない設定がある。

不足する雛形作成はPhase 0のタスクとして先に実施し、Phase 1の機能タスクに混在させない。全体計画上のPhase 0完了にはAndroidの初期化も含まれるが、PC Core自体の動作確認にAndroid実機やAndroidアプリの機能実装は必要ない。

### 3.2 この計画で採用する選択肢

| 項目 | 採用案 | 理由・制約 |
| --- | --- | --- |
| ORM | SQLAlchemy | Pydanticの入出力モデルとDBモデルを分けて扱う |
| Schema変更 | Alembic | 初回からmigrationを唯一のschema管理経路とする |
| Python型チェック | mypy | Phase 0で別の型チェッカーが導入済みならそちらへ統一する |
| ID | ULIDの標準形式、26文字の大文字文字列 | AppSessionにはLifeTimelineのSession生成時に付与し、保存・同期・再送で同じ値を使う。生成・検証は既存ライブラリを利用する |
| DB時刻・期間 | UTC epoch msの`*_at_ms`と`duration_ms`をINTEGERで保存 | `data-model.md`の列名・単位に統一し、秒への丸めで精度を失わない |
| API時刻 | RFC 3339のUTC文字列、ミリ秒付き、末尾`Z` | 例: `2026-09-03T00:00:00.000Z` |
| 日付の基準 | IANAタイムゾーンをリクエストに指定、省略時は`Asia/Tokyo` | サーバーOSのローカル時刻に結果を依存させない |
| Frontend日付処理 | 日付選択と、Backendが計算済みの表示区間・時間・継続フラグの整形 | `Intl.DateTimeFormat`で指定zoneへ表示する。日跨ぎ判定・区間の切り詰め・当日分時間の再計算は行わない |
| 開発時接続 | Viteの`/api` proxy → `127.0.0.1:8000` | Frontendは相対URLでAPIへ接続する |
| アクセス範囲 | FastAPI / Viteともloopbackにbind | Tailscale Serveの設定はPhase 2で行う |

依存バージョンはPhase 0の環境に合わせて互換性を確認し、依存定義とlockファイルへ固定する。本計画では未検証のバージョン番号を指定しない。

## 4. 想定ディレクトリと責務

以下はPhase 0の雛形に合わせて調整する構成案。役割の分離を維持し、同じ責務のディレクトリを二重に作らない。

```text
backend/
├── pyproject.toml
├── alembic.ini
├── migrations/
│   ├── env.py
│   └── versions/
├── app/
│   ├── main.py
│   ├── config.py
│   ├── db.py
│   ├── models/               # SQLAlchemyのテーブル定義
│   ├── schemas/              # PydanticのAPI定義
│   ├── repositories/         # Master / Factの保存・区間検索・集計Query
│   ├── services/
│   │   ├── time_range.py     # 日付範囲・切り詰め・当日分時間・継続判定
│   │   ├── timeline.py       # Timeline用の変換
│   │   └── statistics.py     # 原本からの集計
│   ├── api/
│   │   ├── timeline.py       # TimelineのHTTP入出力
│   │   └── statistics.py     # StatisticsのHTTP入出力
│   └── cli/seed.py           # 開発用データ投入
└── tests/
    ├── unit/
    ├── api/
    └── db/
frontend/
├── src/
│   ├── app/                 # Router / 画面共通設定
│   ├── features/timeline/    # 画面・日付操作・項目表示
│   ├── features/dashboard/   # 合計値・アプリ別統計
│   └── lib/                 # API client・共通の日付表示処理
└── e2e/
docs/
└── detailed_plan/phase1-pc-core.md
```

HTTPルートに直接SQLを書かず、日付範囲の決定とDB検索を分離する。Statisticsは正規化データから生成し、Timeline APIのレスポンスやFrontendの一覧を集計元にしない。日跨ぎ判定・区間の切り詰め・当日分時間の計算はBackendだけで行い、両Queryで同じ規約を使う。Frontendは計算済みの値を整形して表示し、日付状態を両表示で共有する。汎用Collector基盤やプラグイン機構は導入しない。

## 5. 保存先・DB・migration

### 5.1 データディレクトリ

- 設定名は`LIFE_TIMELINE_DATA_DIR`とする。
- Windowsで未指定の場合は`%LOCALAPPDATA%/life-timeline`を既定値とし、`lifelog.db`を置く。
- 指定値は絶対パスへ解決してBackend・Alembic・seedで共有し、カレントディレクトリの違いで別DBを開かないようにする。
- 開発用デモはリポジトリ内の`data/demo/`、テストはテストごとの一時ディレクトリへ明示的に分離する。
- `data/`、`*.db`、`*.db-wal`、`*.db-shm`、ローカル環境設定をGit管理対象外にする。
- 保存先を作成できない、または書き込めない場合は起動を失敗させ、別の場所やin-memory DBへ黙って切り替えない。
- `thumbnails/`・`exports/`・`backups/`は対応フェーズで追加する。

### 5.2 初期schema

DBの列名は`data-model.md`に合わせたsnake_case、APIのプロパティ名はcamelCaseとする。SQLiteのTEXT PRIMARY KEYも含め、必須列には明示的にNOT NULLを付ける。

#### devices

| 列 | SQLite型 | 制約・意味 |
| --- | --- | --- |
| `id` | TEXT | PRIMARY KEY、ULID |
| `name` | TEXT | NOT NULL、1〜200文字 |
| `platform` | TEXT | NOT NULL、`android` / `windows` |
| `created_at_ms` | INTEGER | NOT NULL、PCで保存した時刻、UTC epoch ms |
| `last_seen_at_ms` | INTEGER | NULL可、実際の受信・確認時刻。seedではNULL |

`last_seen_at_ms`をTimeline / Dashboardの閲覧だけで更新しない。端末登録APIと端末管理画面は作らない。

#### categories

| 列 | SQLite型 | 制約・意味 |
| --- | --- | --- |
| `id` | TEXT | PRIMARY KEY、ULID |
| `name` | TEXT | NOT NULL、1〜200文字 |
| `created_at_ms` | INTEGER | NOT NULL、PCで保存した時刻、UTC epoch ms |

fixture用カテゴリだけをseedする。本番向けカテゴリの初期セットや自動分類は導入しない。

#### apps

| 列 | SQLite型 | 制約・意味 |
| --- | --- | --- |
| `id` | TEXT | PRIMARY KEY、ULID |
| `platform` | TEXT | NOT NULL、`android` / `windows` |
| `identifier` | TEXT | NOT NULL、1〜255文字。Androidのpackage名、Windowsの`Code.exe`等 |
| `display_name` | TEXT | NOT NULL、1〜255文字。名称不明時は`identifier`を保存する |
| `category_id` | TEXT | NULL可、`categories.id`への外部キー |
| `icon_path` | TEXT | NULL可、Phase 1ではNULL。アイコン取得・配信は対象外 |
| `created_at_ms` | INTEGER | NOT NULL、PCで保存した時刻、UTC epoch ms |

- 同一アプリのMaster重複を避けるため、Phase 1の具体化として`UNIQUE(platform, identifier)`を置く。同じアプリを複数端末が利用しても同じ`app_id`を参照する。
- 名称が同じでもplatform・identifierが異なるアプリは別Masterとする。表示名を一意キーにしない。
- Phase 1のfixtureではidentifierの表記を固定して完全一致で照合する。Windowsのパス・大文字小文字等の収集元固有の正規化はPhase 6で決める。

#### app_sessions

| 列 | SQLite型 | 制約・意味 |
| --- | --- | --- |
| `id` | TEXT | PRIMARY KEY、LifeTimelineが生成した正規化AppSessionのULID（LifeTimeline AppSession ID） |
| `device_id` | TEXT | NOT NULL、`devices.id`への外部キー |
| `app_id` | TEXT | NOT NULL、`apps.id`への外部キー |
| `started_at_ms` | INTEGER | NOT NULL、UTC epoch ms |
| `ended_at_ms` | INTEGER | NOT NULL、UTC epoch ms、`started_at_ms`より後 |
| `duration_ms` | INTEGER | NOT NULL、`ended_at_ms - started_at_ms`と等しい正の整数 |
| `source` | TEXT | NOT NULL、1〜100文字の空白のみではない収集元識別子。例: `android_usage_stats` / `activitywatch` |
| `created_at_ms` | INTEGER | NOT NULL、PCで最初に保存した時刻、UTC epoch ms |

- `id`はこのFact内で全端末共通の一意キーとする。同じIDを別端末の記録として追加せず、競合として扱う。将来の`desktop_session_details.session_id`もこの単一IDを参照する。
- AppSessionへアプリ名・identifier・categoryを複製せず、`apps`への参照を保存する。表示と集計にはMasterの現在値を使い、名前・分類の履歴管理は対象外とする。
- 外部キーは削除時にRESTRICTとし、端末・アプリ・カテゴリの削除による履歴の連鎖削除を避ける。
- DBにも`CHECK(ended_at_ms > started_at_ms)`と`CHECK(duration_ms = ended_at_ms - started_at_ms)`を置く。
- `AppSessionRepository`では`devices.platform = apps.platform`を検証する。その他はID・外部キー・時刻・duration・文字列形式などの汎用的な整合性を保証し、sourceとplatformの固定対応は検証しない。
- `source`を既知Collectorのenumや許可リストにせず、DBのCHECKにもsourceとplatformの対応を埋め込まない。`screenpipe`や別Collectorの追加でRepository・schemaの修正を要求しない。
- Collector / Adapterが収集元固有の検証を担当する。例えば`ActivityWatchAdapter`が`source = "activitywatch"`と`platform = "windows"`を保証し、正規化AppSessionをRepositoryへ渡す。実Adapterの実装・テストは対応フェーズで行う。
- `idx_app_sessions_started`を`started_at_ms`に作成する。`(app_id, started_at_ms)`はデータモデルのindex候補とし、実Queryの計測で採用を判断する。一意制約と重複するindexは追加しない。
- Phase 1では終了が確定したSessionだけを保存する。進行中Session、UsageStatsのSession化、収集時の重複イベント検出はPhase 2の課題とする。
- `sync_state`や`pending` / `syncing` / `synced`はPC側Factには追加しない。これらはAndroid側の同期状態である。

### 5.3 書き込みの規約

#### AppSession IDの意味と生成タイミング

AppSessionはOSから直接得る生イベントではなく、LifeTimelineのSessionizerが生成する正規化データである。例えばforeground / backgroundの複数イベントを1つの利用区間へまとめ、そのSessionへULIDを一度だけ付与する。

```text
UsageStats raw events
    ↓
LifeTimeline Sessionizer: 利用区間を生成
    ↓
AppSession ID（ULID）を一度だけ生成
    ↓
IDを含むAppSessionをRoomへ永続化
    ↓
保存済みAppSessionをPCへ同期
    ↓ 通信失敗・ACK未受信
Roomから同じAppSession IDを読み、再送
```

- Phase 2では、ID付きAppSessionのRoom保存が完了してから同期対象にする。同期・再試行のたびにULIDを生成しない。
- PCは受信したAppSession IDをそのまま保存する。Repositoryや同期受信処理でIDを付け直さず、Timeline取得時にも新しいIDを生成しない。
- 同じSessionの再送は同じID・内容として冪等に処理する。OSイベントIDやActivityWatchの`source_event_id`は収集元を参照する別の識別子であり、AppSession IDと同一視しない。
- Phase 1のseedは、LifeTimelineが生成済みのSessionを模した固定ULIDを使用する。Sessionizer・Room・同期の実装はPhase 2へ残す。
- 生イベントの再取得・再処理によって同じSessionを別IDで生成し直さないための照合・復旧規約はPhase 2で設計する。再送時に同じIDを使うことだけで、再処理時の重複まで解決したとは扱わない。

#### Repositoryの保存処理

1. 入力を検証し、タイムゾーンを持つ時刻をUTC epoch msへ正規化する。
2. `duration_ms`は入力値を信用せず、整数ミリ秒の時刻差から算出する。
3. カテゴリ・端末・アプリのMasterを保存・解決した上で、Sessionへ`device_id`と`app_id`を設定する。同じ`(platform, identifier)`のアプリは既存IDを再利用する。
4. Masterの新規保存とSessionの保存を同じトランザクションで行い、失敗時は全体をrollbackする。
5. 渡されたLifeTimeline AppSession IDを保持する。同じID・端末・アプリ・区間・sourceの再投入は成功とし、件数・`created_at_ms`を変えない。
6. 同じSession IDで端末または内容が異なる場合は、競合として全体を失敗させる。既存の履歴を暗黙に上書きしない。
7. seedのMasterも固定ID・固定内容で照合する。同じ自然キーに異なるIDを渡した場合は既存IDへ解決し、同じIDの別自然キーへの付け替えや名称・分類の競合はエラーにする。通常の名称更新・カテゴリ変更APIはPhase 1では作らない。

Phase 2の同期APIでは、この一意性を引き継いでACK・再送・必要に応じた更新規約を定義する。Phase 1で同期プロトコルを実装済みとは扱わない。

### 5.4 SQLite接続とmigration

- 外部キー制約を各DB接続で有効にし、ロック待ちのtimeoutを設定する。
- APIリクエスト単位でDB Sessionを生成・終了し、グローバルなSessionを使い回さない。
- 初回revisionで`devices`・`categories`・`apps`・`app_sessions`の4テーブル・制約・indexを作成する。現時点は未実装のため、旧2テーブルからの移行revisionは不要。着手時に旧schemaのDBが存在した場合は、削除せずデータ変換migrationを別途計画する。
- 通常起動・seed・テストで`create_all()`によるmigrationの迂回を行わない。
- 初期化手順は`alembic upgrade head`とし、適用前またはschema不一致での起動は具体的な案内付きで失敗させる。
- migrationは明示的なコマンドで行い、サーバー起動時に無条件で実行しない。
- 空DB → head、headへの再適用、データ投入後の再起動での保持を検証する。
- downgradeの確認は使い捨てテストDBだけで行う。データ保持を目的とする通常運用で、DB削除・初回revisionへの巻き戻しを復旧手順にしない。

## 6. 日付・時刻・区間の規約

### 6.1 日付検索

指定したタイムゾーンにおける「対象日の00:00」と「翌日の00:00」をそれぞれUTCへ変換し、半開区間`[rangeStart, rangeEnd)`を作る。

例: `date=2026-09-03&timezone=Asia/Tokyo`

```text
表示対象日: 2026-09-03 00:00:00 ～ 2026-09-04 00:00:00 JST
rangeStart: 2026-09-02T15:00:00.000Z
rangeEnd:   2026-09-03T15:00:00.000Z
```

検索条件は以下とする。

```sql
started_at_ms < :range_end_ms
AND ended_at_ms > :range_start_ms
```

- 日跨ぎSessionは重なる両方の日に表示する。
- 対象日の00:00ちょうどに終了したSessionは含めない。
- 翌日00:00ちょうどに開始したSessionは含めない。
- UTCの暦日やDBの`DATE()`だけで対象日を決めない。
- 翌日の境界はローカル暦日の翌日から求める。UTC時刻への24時間加算で済ませない。
- BackendはIANAタイムゾーンの解決に必要なデータをWindowsでも利用できるよう依存管理する。`zoneinfo`と`tzdata`を用いる構成を採用し、Windows上で検証する。
- 内部変換でtimezoneなしのdatetimeを受け取った場合はエラーとし、PCのローカルタイムを推測して補完しない。

### 6.2 Backendが生成する表示用区間

DBには元のSession区間・durationを保持する。Timeline APIは元の`startedAt`・`endedAt`・`durationMs`に加え、対象日の表示に必要な計算結果を`display`として返す。`display`はDBへ保存しない。

Backendで以下を計算する（比較・差分は整数ミリ秒で行い、時刻はレスポンス化するときにRFC 3339へ変換）。

| `display`のフィールド | Backendでの計算・意味 |
| --- | --- |
| `startedAt` | `max(started_at_ms, rangeStartMs)`をUTC文字列に変換 |
| `endedAt` | `min(ended_at_ms, rangeEndMs)`をUTC文字列に変換 |
| `durationMs` | 上記の切り詰め後の終了−開始。当日分の利用時間 |
| `continuesFromPreviousDay` | `started_at_ms < rangeStartMs` |
| `continuesToNextDay` | `ended_at_ms > rangeEndMs` |
| `endsAtDayBoundary` | 切り詰め後の終了が`rangeEndMs`と等しい |

- Frontendは`display.startedAt` / `endedAt`を指定zoneの時刻へ整形し、`display.durationMs`を時間・分に整形する。元区間と日付境界の比較、min / max、時刻差の計算は行わない。
- `continuesFromPreviousDay` / `continuesToNextDay`がtrueなら、対応する「前日から継続」/「翌日へ継続」を表示する。Frontendで継続の有無を判定し直さない。
- `endsAtDayBoundary`がtrueなら終了を`24:00`と表記する。ちょうど翌日00:00に終了したSessionでは、この値はtrue、`continuesToNextDay`はfalseとなる。
- 1分未満の利用は「1分未満」、それ以上は時間・分で表示する。これらは計算済みdurationの表示整形であり、日跨ぎ計算ではない。
- 日付入力・前日/翌日への暦日の移動はFrontendの操作として残すが、Sessionの分割・当日分時間の計算には使わない。
- APIの表示順は元の`startedAt`昇順。同時刻なら`deviceId`、`id`の順で安定させ、Frontendでは並べ替えない。

### 6.3 統計に使う時間と件数

- 統計の対象Sessionにも§6.1の重複条件を適用する。
- Backendで各Sessionの対象期間内の利用時間を`max(0, min(ended_at_ms, rangeEndMs) - max(started_at_ms, rangeStartMs))`として計算する。§6.2と共通の区間計算を用い、整数ミリ秒のまま集計して返す。Frontendは時間・分への表示整形だけを行う。
- 日跨ぎでは元の`duration_ms`全量を各日に加算せず、該当日へ切り詰めた時間だけを使う。
- `sessionCount`は対象期間と重なる異なるSession IDの件数。日跨ぎSessionは重なる各日の件数に1件ずつ含まれるが、複数日の一括集計では1件となる。日別件数の合計と期間全体の件数は必ずしも一致しない。
- 同時利用・複数端末の時間は各Sessionの記録時間を加算し、重なる区間を統合しない。合計は人の実活動時間や排他的な画面使用時間を意味せず、1日の長さを超える場合もある。Dashboardでは「記録された利用時間の合計」と表示する。
- アプリ別集計は`app_id`をキーにし、同じアプリを使った複数端末の記録をまとめる。同名でもAndroid / Windowsのアプリは別行となる。
- BackendのAPIテストで、同じ範囲に対して「Timelineの各itemの`display.durationMs`の合計 = Statisticsの`totals.usageMs`」をミリ秒単位で検証する。Frontendに検算・再集計は実装しない。正規化データはどちらの取得でも更新しない。

## 7. Timeline / Statistics API契約

### 7.1 Timelineエンドポイント

```http
GET /api/v1/timeline?date=2026-09-03&timezone=Asia%2FTokyo
```

| パラメータ | 必須 | 規約 |
| --- | --- | --- |
| `date` | 必須 | `YYYY-MM-DD`の実在する日付。翌日境界を計算できる日付範囲を許可する |
| `timezone` | 任意 | 有効なIANAタイムゾーン。省略時`Asia/Tokyo` |

取得処理は書き込みを行わず、`app_sessions`へ`devices`・`apps`をJOINしてTimeline itemへ変換する。Phase 1の種類はAndroid / Windows共通の`app_session`のみ。Master情報をレスポンスへ展開する処理はQueryレイヤーに置く。

上位の技術設計§9にある配列レスポンスは例として扱い、本計画では`items`と検索範囲を持つオブジェクト形式へ具体化する。検索範囲はリクエストとの対応確認用であり、Frontendでの日跨ぎ計算には使わない。各itemの`display`にBackendの計算結果を含め、実装時はAPI仕様・Frontend型・上位設計の例を同じ契約へ揃える。

### 7.2 Timelineの正常レスポンス

```json
{
  "date": "2026-09-03",
  "timezone": "Asia/Tokyo",
  "rangeStart": "2026-09-02T15:00:00.000Z",
  "rangeEnd": "2026-09-03T15:00:00.000Z",
  "items": [
    {
      "type": "app_session",
      "id": "01ARZ3NDEKTSV4RRFFQ69G5FAW",
      "deviceId": "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "deviceName": "Demo Android",
      "platform": "android",
      "appId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
      "appIdentifier": "com.android.chrome",
      "appName": "Chrome",
      "source": "android_usage_stats",
      "startedAt": "2026-09-03T00:00:00.000Z",
      "endedAt": "2026-09-03T00:20:00.000Z",
      "durationMs": 1200000,
      "display": {
        "startedAt": "2026-09-03T00:00:00.000Z",
        "endedAt": "2026-09-03T00:20:00.000Z",
        "durationMs": 1200000,
        "continuesFromPreviousDay": false,
        "continuesToNextDay": false,
        "endsAtDayBoundary": false
      }
    }
  ]
}
```

- itemフィールドはすべて必須。`appName`は`apps.display_name`、`appIdentifier`は`apps.identifier`から取得する。名称不明時はMasterへ保存済みのidentifierが表示名になる。
- itemのIDは保存済みのLifeTimeline AppSession IDのまま返す。同じSessionが別日のTimelineに出ても同じIDを使い、表示日ごとのIDを生成しない。Frontendのkeyには`type + deviceId + id`を使う。
- item直下の時刻・durationは原本の値、`display`内は対象日へ切り詰め済みの値とする。画面は`display`を参照し、直下の値から再計算しない。
- 時刻は表示用のRFC 3339文字列、期間は整数ミリ秒とする。DBの`*_at_ms`をそのまま表示用schemaに流用しない。
- `created_at_ms`、DBパス、内部例外、同期状態は返さない。
- 記録がない場合は`200`と`items: []`を返す。
- Phase 1は対象日の全件を返し、ページングは導入しない。件数制限による黙った切り捨てをしない。

例えばJSTの09-02 23:50〜09-03 00:10というSessionを09-03で取得すると、元のdurationは1,200,000 msのまま、`display`は次のようになる。

```json
{
  "startedAt": "2026-09-02T15:00:00.000Z",
  "endedAt": "2026-09-02T15:10:00.000Z",
  "durationMs": 600000,
  "continuesFromPreviousDay": true,
  "continuesToNextDay": false,
  "endsAtDayBoundary": false
}
```

Reactはこの計算済みの値から「00:00〜00:10 / 10分 / 前日から継続」を表示する。

### 7.3 共通のエラー

| 状態 | HTTP | code | Frontendの扱い |
| --- | --- | --- | --- |
| 日付未指定・形式不正・存在しない日付・範囲外 | 422 | `invalid_request` | 日付を確認する案内 |
| Statisticsの`from` / `to`欠落・不正・`from >= to` | 422 | `invalid_request` | 対象期間を確認する案内 |
| 解決できないタイムゾーン | 422 | `invalid_request` | タイムゾーンを確認する案内 |
| DB読み取り失敗などの予期しないエラー | 500 | `internal_error` | 取得失敗と再試行ボタン |
| 接続不可・タイムアウト | HTTP応答なし | API client側の接続エラー | Backend起動確認の案内と再試行ボタン |

APIのエラー形は、FastAPIの入力検証エラーを含めて以下へ統一する。

```json
{
  "error": {
    "code": "invalid_request",
    "message": "dateはYYYY-MM-DD形式で指定してください。",
    "field": "date"
  }
}
```

`field`は対象パラメータがある場合のみ返す。詳細な内部例外はサーバーログに記録し、レスポンスへ含めない。API clientはJSON以外のエラー応答でも画面をクラッシュさせない。

### 7.4 基本Statistics API

```http
GET /api/v1/stats/apps?from=2026-09-03&to=2026-09-04&timezone=Asia%2FTokyo
```

| パラメータ | 必須 | 規約 |
| --- | --- | --- |
| `from` | 必須 | `YYYY-MM-DD`の実在する開始日。指定zoneの00:00を含む |
| `to` | 必須 | `YYYY-MM-DD`の実在する終了境界日。指定zoneの00:00を含まない。`from < to` |
| `timezone` | 任意 | Timelineと同じ。有効なIANAタイムゾーン、省略時`Asia/Tokyo` |

期間は`[fromの00:00, toの00:00)`とする。Phase 1のDashboardは選択日を`from`、翌日を`to`として取得する。APIは期間合計を返すが、日別推移・週次/月次のグラフや専用の期間選択UIはPhase 7に残す。

以下は§7.2と同じ1件だけを保存した場合のレスポンス例であり、デモfixture全体の期待値ではない。

```json
{
  "from": "2026-09-03",
  "to": "2026-09-04",
  "timezone": "Asia/Tokyo",
  "rangeStart": "2026-09-02T15:00:00.000Z",
  "rangeEnd": "2026-09-03T15:00:00.000Z",
  "totals": {
    "usageMs": 1200000,
    "sessionCount": 1,
    "appCount": 1
  },
  "items": [
    {
      "appId": "01ARZ3NDEKTSV4RRFFQ69G5FAX",
      "platform": "android",
      "appIdentifier": "com.android.chrome",
      "appName": "Chrome",
      "usageMs": 1200000,
      "sessionCount": 1
    }
  ]
}
```

- 元の`app_sessions`と`apps`を使って§6.3の規約で集計する。Timeline APIを内部呼び出ししたり、`TimelineItem[]`を集計元にしたりしない。
- `totals.usageMs` / `sessionCount`は各行の合計、`appCount`は対象期間に利用記録がある`app_id`の数とする。未使用のMasterは行にも件数にも含めない。
- 並び順は`usageMs`降順、同値なら`appId`昇順。名前が同じアプリを勝手に統合しない。
- 0件は`200`、`items: []`、`totals`の各値を0として返す。
- カテゴリ・端末ごとの内訳、平均時間、割合、推移はこのAPIのPhase 1契約に含めない。
- キャッシュ用テーブルや集計結果の保存は行わず、何度取得してもMaster / Factは変化しない。

## 8. ダミーデータ投入計画

### 8.1 seedコマンド

- CLIのみを用意し、公開HTTP APIにseed・DB削除機能を作らない。
- `python -m app.cli.seed --data-dir <デモ用絶対パス>`という形で、投入先の明示を必須にする。
- APIが参照するデータディレクトリと同じ設定処理を使う。schema未適用の場合はmigrationの案内を出して終了する。
- 実在端末の個人データを使わず、固定したカテゴリ・端末・アプリ・LifeTimeline AppSession IDと時刻から作る。
- fixtureはAPI例と同じく有効なULIDを使う。fixtureのIDに含まれる生成日時をイベント発生日時として扱わない。
- 再実行時は同一データを増やさず、投入件数・既存件数を表示する。DB全削除を伴うresetオプションはPhase 1では用意しない。
- 競合や不正fixtureが1件でもあれば、Master登録を含む今回の投入全体をrollbackする。

### 8.2 fixtureの構成

基準タイムゾーンを`Asia/Tokyo`、固定日を2026-09-02〜2026-09-04とする。今日の日付に追随させず、検証の再現性を優先する。画面確認手順には固定日のURLを記載する。

| fixture | 目的 |
| --- | --- |
| Demo Android A / B、Demo Windowsの3端末 | 共通AppSessionで両platformを保存・表示・集計できることを確認 |
| 2台のAndroidで同じアプリを利用 | 1つの`apps`行を共有し、複数Sessionをアプリ別統計へまとめることを確認 |
| Android / Windowsに同じ表示名のChrome | `app_id`が異なるため統計で別行になることを確認 |
| デモカテゴリあり・`category_id = null`のアプリ | 分類がなくてもSession保存・両Queryが動くことを確認 |
| 複数アプリ、同じアプリの別Session | Timelineの区間表示とStatisticsの集計を同じ原本から確認 |
| 2026-09-02 23:50〜09-03 00:10 | 日跨ぎの当日分10分を両表示で確認 |
| 09-03 00:00終了、09-04 00:00開始のSession | 半開区間の境界を確認 |
| 09-03 09:00〜09:20のChrome | API例と画面上の時刻・20分表示を照合 |
| 同時刻に始まる異なる端末・IDのSession | 安定したソートとReact keyを確認 |
| 名称不明、日本語・長い`display_name` | identifierを表示名として保存する規約と表示の崩れを確認 |
| 1分未満・ミリ秒を含むSession | 保存・集計では丸めず、表示時のみ時間単位を変換することを確認 |
| 2026-09-05は0件 | 空状態を確認 |

上表はfixtureの条件。実装時に各レコードを固定し、日付ごとの期待件数・順序・`display`の区間とフラグ・アプリ別利用時間・合計をfixture側に明記する。デモ用の`source`はAndroidに`android_usage_stats`、Windowsに`activitywatch`を設定するが、これはfixtureの選択でありRepositoryの制約ではない。別sourceの保存確認、不正区間・孤立外部キー・DST・大量件数は自動テスト専用データとし、通常デモへ混在させない。

集計の基準テストには、次の4件だけを入れる独立したfixtureを用意する（全時刻JST）。

| 端末 / アプリ | 区間 | 2026-09-03に含む時間 |
| --- | --- | --- |
| Android A / Chrome | 09-02 23:50〜09-03 00:10 | 600,000 ms |
| Android A / Chrome | 09-03 09:00〜09:20 | 1,200,000 ms |
| Android B / Chrome（Aと同じ`app_id`） | 09-03 09:10〜09:15 | 300,000 ms |
| Windows / Chrome（別`app_id`） | 09-03 09:00〜09:30 | 1,800,000 ms |

期待値はAndroid Chromeが2,100,000 ms・3件、Windows Chromeが1,800,000 ms・1件、合計3,900,000 ms（65分）・4件・2アプリ。端末間の時間重複を除去せず、日跨ぎの当日分だけを加算する。

## 9. React Timeline / 最小Dashboardの実装計画

### 9.1 画面と状態

初期ルートは`/timeline`とし、`/`から遷移する。最小Dashboardは同じ画面の上部に配置し、その日の合計値とアプリ別統計を表示する。URLには両表示で共有する選択日とタイムゾーンを保持する。独立した本格Statistics画面はPhase 7で追加する。

```text
/timeline?date=2026-09-03&timezone=Asia%2FTokyo
```

```text
life-timeline

[前日] [2026-09-03 ▼] [翌日] [今日]     Asia/Tokyo

Dashboard
記録された利用時間の合計 / セッション数 / 利用アプリ数
アプリ別: アプリ名・platform・利用時間・セッション数の表

Timeline
09:00 – 09:20   Chrome
                 Android / Demo Android A / 20分

09:32 – 09:40   Google Maps
                 Android / Demo Android A / 8分
```

- URLの有効な日付・タイムゾーンを画面状態の基準とし、再読み込み・ブラウザの戻る/進むに対応する。
- URLにタイムゾーンがない初回はブラウザのIANAタイムゾーンを採用し、取得できない場合は`Asia/Tokyo`へfallbackする。
- 日付がない初回は、採用したタイムゾーンの「今日」を使う。
- 不正なURL値は入力エラーとして表示し、今日へ戻す操作を用意する。不正値で取得を繰り返さない。
- 前日・翌日は選択タイムゾーンの暦日を1日移動する。日付の計算可能範囲を超える操作は無効にする。
- Timelineの`date`、Statisticsの`from` / `to`と、両方の`timezone`・UTC範囲を現在の選択と対応させて表示する。別日や別zoneの値を並べない。
- React上は`type: "app_session"`を持つ型を定義し、後続フェーズでDiscriminated Unionへ追加できる構造にする。架空の将来itemは定義しない。
- DashboardにはStatistics専用のレスポンス型を定義する。`TimelineItem`を統計用に流用せず、FrontendでTimelineから集計値を計算しない。

### 9.2 表示要件

- アプリ名、端末名、プラットフォームと、Backendが返す`display`の利用区間・当日分時間・継続フラグを表示する。Reactでは日跨ぎ判定・区間の切り詰め・当日分時間を計算しない。
- `appName`を表示し、名称不明のアプリでは保存時にfallbackしたidentifierがそのまま表示される。
- Dashboardは`totals`の合計時間・Session件数・アプリ数と、アプリ別の時間・Session件数を表示する。platformを併記し、同名アプリを区別できるようにする。
- Dashboardの合計は0件なら「0分・0件・0アプリ」。API失敗時は0として表示しない。
- Timelineは`display.durationMs`、Dashboardは`usageMs`を時間・分へ整形する。タイムスタンプ同士の差を取らず、アプリごとに丸めた表示値を足して合計を作らない。
- 長いアプリ名が横幅を押し広げないよう折り返す。本文はテキストとして描画し、HTMLとして解釈しない。
- ローディング中・正常・空・エラーを明確に分ける。
- 両APIは独立して取得し、片方だけ失敗した場合は該当領域にエラーと再試行を表示する。成功した側は表示を維持し、失敗した側をTimeline由来の仮集計などで補完しない。
- 日付切替時は旧日の一覧・統計を新しい日付の記録として表示しない。
- 連続で日付を切り替えた場合は、リクエスト中断または応答の識別により古い応答の上書きを防ぐ。
- 取得失敗時の再試行は同じ日付・タイムゾーンで行う。無制限な自動再試行は行わない。
- 日付入力・移動ボタンにラベルを付け、キーボードで操作できるようにする。PCのウィンドウ幅を狭めても基本操作を維持する。

## 10. 実装タスクと依存関係

各IDは計画内の識別子であり、GitHub Issue番号ではない。実装時は1タスクを原則1 Issue / 1 PRとし、目的・実装内容・完了条件をIssue本文へ移す。

E2EをPR必須にするため、最初のPC Core実装PRは例外としてP1-01〜05の最小経路とP1-07のE2E基盤を一緒に構築する。実SQLiteへの保存 → 実API → React描画が成功してからmergeし、その後のPRで各タスクの残りのケースを追加する。P1-07までE2E導入を待ったり、経路未完成を理由に成功扱いのskipを置いたりしない。

```text
Phase 0のPC基盤
    ↓
P1-01 保存先・DB接続
    ↓
P1-02 正規化schema・保存処理
    ├──→ P1-03 seed
    └──→ P1-04 Timeline / Statistics API
               ↓
          P1-05 Timeline / 最小Dashboard
               ↓
          P1-06 日付・画面状態

P1-01〜06で各機能のテストを追加
    ↓
P1-07 CI・必須E2Eの拡充と最終確認 ← 最小基盤は最初の実装PRから導入
    ↓
P1-08 起動手順・受け入れ確認
```

### P1-01: データディレクトリとDB接続を整備する

- **目的:** 起動場所に依存せず同じSQLiteファイルへ接続する。
- **依存:** Phase 0のBackend雛形。
- **作業:** 設定と絶対パス解決、ディレクトリ作成、SQLAlchemy Engine / Session、外部キー・timeout、Git除外設定を追加する。
- **成果物:** `config.py`、`db.py`、設定例、接続テスト。
- **完了条件:** API・CLIから共通設定を使用でき、一時ディレクトリで作成・再接続できる。書き込み不可時に原因が分かる。

### P1-02: 正規化schemaの初期migrationと保存処理を実装する

- **目的:** Masterと共通AppSessionを分離して永続化し、表示形式から独立した原本を保持する。
- **依存:** P1-01。
- **作業:** 4モデル、Alembic初回revision、app自然キーの解決、FK・端末とアプリのplatform一致・sourceの汎用文字列検証、`duration_ms`算出、トランザクション、渡されたAppSession IDの保持・再投入・競合処理を追加する。source固有の対応付けはRepositoryに実装しない。
- **成果物:** `devices`・`categories`・`apps`・`app_sessions`、repository、migration / DBテスト。
- **完了条件:** 空DBからheadへ到達できる。複数端末の同じアプリがMasterを共有し、Android / Windowsを同じFactへ保存できる。別sourceも汎用的な制約を満たせば保存でき、同じAppSession IDの再投入で増えず、不正入力・ID競合はrollbackされる。

### P1-03: 再実行可能なデモ用seedを作成する

- **目的:** AndroidやActivityWatchがなくてもTimelineと統計を同じデータで確認する。
- **依存:** P1-02。
- **作業:** 固定fixture、投入先必須のCLI、投入結果表示、再実行・競合時rollbackの確認を追加する。
- **成果物:** seed CLI、Master / Factのfixture、日付別の表示・集計期待値。
- **完了条件:** 2回投入しても件数と内容が変わらない。指定したデモDBだけへ書き込まれ、§8の表示ケースが揃う。

### P1-04: Timeline APIと基本Statistics APIを実装する

- **目的:** 同じ正規化データから、指定日の記録と対象期間のアプリ別集計を返す。
- **依存:** P1-02。
- **作業:** 共通の期間検証・UTC境界・区間重複検索、Backendでの切り詰め・当日分時間・継続フラグ計算、MasterのJOIN、`display`付きTimeline変換、原本からのアプリ別集計、両APIのschema・エラー変換・OpenAPIを実装する。
- **成果物:** Timeline / Statisticsのroute・service・Query、共通の時刻処理、API・集計テスト。
- **完了条件:** §7の両契約に一致し、空日・日跨ぎ・同時刻・DST・複数端末・不正期間を検証できる。§8の基準fixtureで3,900,000 ms・4件・2アプリとなり、Timeline当日分と統計が一致する。GETでDB内容が変わらない。

### P1-05: Timelineと最小Dashboardの取得・表示を実装する

- **目的:** 同じSQLiteの記録をReactの時系列と基本統計の両方で表示する。
- **依存:** P1-04の契約確定、Phase 0のFrontend雛形。
- **作業:** 両レスポンスのTypeScript型、API client、Vite proxy、Router、計算済み`display`を描画するTimeline item、Dashboardの合計値・アプリ別表、時刻・ミリ秒・フラグの表示整形を追加する。日跨ぎの計算処理はFrontendへ追加しない。
- **成果物:** TimelineとDashboardを持つ画面、API client、コンポーネントテスト。
- **完了条件:** seedした固定日を両APIから表示できる。共通モデルのAndroid / Windowsが読め、片方のAPI失敗も区別できる。

### P1-06: 日付切替・URL状態・表示状態を仕上げる

- **目的:** 過去日を安定して閲覧し、再読み込みでも選択を保持する。
- **依存:** P1-05。
- **作業:** 共通の日付入力、前日・翌日・今日、URL同期、戻る/進む、両表示の空・loading・error・再試行、古い応答の破棄、基本アクセシビリティを追加する。
- **成果物:** 日付操作コンポーネント、状態遷移テスト。
- **完了条件:** 日付と一覧・統計が常に対応する。素早い日付切替でも取り違えず、日跨ぎの当日分時間が両表示で正しい。

### P1-07: 実DBを使ったE2EをPR必須にする

- **目的:** 実DB → API → Reactの接続を、すべてのPRで確認する必須ゲートにする。
- **依存:** P1-01〜05の最小経路と同時に開始し、P1-03〜06の完成に合わせて代表シナリオを拡充する。
- **作業:** 一時SQLiteへのmigration・seed、実FastAPI / React起動、Playwright実行をCIへ組み込む。代表E2Eの固定チェック名を`pc-core-e2e`とし、mainの保護ルール / rulesetでRequired status checkに指定する。失敗時のtrace・ログを保存する。
- **成果物:** PRごとに動くE2E job、必須チェック設定、代表シナリオ、検証結果。
- **完了条件:** lint・型チェック・test・build・migrationと必須E2Eが成功する。実DB → 両API → Timeline / Dashboardの表示・一致・日付切替を確認でき、`pc-core-e2e`の失敗・未実行・中断をmerge可能な成功として扱わない。

### P1-08: Windowsの実行手順とPhase 1受け入れを完了する

- **目的:** 新しいcheckoutからPC単体で再現できる状態にする。
- **依存:** P1-07。
- **作業:** READMEへ依存導入・保存先・migration・seed・起動・固定日URL・両表示の確認方法・テストを記載し、§12の手順を実施する。§1に記載した主キー・旧テーブル名・API例の記述差も上位設計と整合させる。
- **成果物:** 起動・トラブルシュート文書、受け入れ記録、後続課題の一覧。
- **完了条件:** 手順どおりに起動して同じダミーデータをTimelineと基本統計で表示できる。未実施項目を成功扱いせず、Phase 2への引き継ぎ事項を残す。

実装時のGitHub Issue・ブランチ・PR運用は技術設計§14に従い、その時点のユーザー指示を優先する。今回の作業は計画書の更新のみで、Issue作成・ブランチ作成・アプリ実装は行わない。

## 11. テスト・CI計画

テストは各タスク内で追加する。P1-07まで検証を後回しにしない。個人の既存DBは使用せず、DB関連テストでは原則として一時ファイルのSQLiteにmigrationを適用する。

### 11.1 Backend

| 分類 | 検証内容 | 期待結果 |
| --- | --- | --- |
| 保存 | 正常なMasterとAndroid / WindowsのSession | 再接続して全項目が一致する |
| 正規化 | 2端末で同じplatform・identifierのアプリ | 1つの`apps`行を参照し、Sessionは端末ごとに残る |
| アプリの区別 | 表示名が同じでplatformまたはidentifierが異なる | 別Master・別集計行となる |
| 任意属性 | カテゴリなし・アイコンなし・名称不明 | nullable列とidentifierによる表示名で保存・両API取得が成功する |
| 冪等性・ID保持 | LifeTimelineが生成済みの同じAppSession ID・内容を再投入して両日から取得 | Repository・APIでIDを再生成せず、件数・`created_at_ms`が変わらない |
| ID境界 | 別端末で同じSession ID | PRIMARY KEY競合として拒否し、既存記録と新端末情報を変更しない |
| 競合 | 同じ端末・IDで異なる内容 | 競合となり投入全体がrollbackされる |
| Master競合 | 同じIDの別自然キー、同じ自然キーの異なるID、内容の不一致 | §5.3どおりIDを再利用するか、競合を拒否する |
| 制約 | 孤立FK、終了≦開始、duration不整合、deviceとappのplatform不一致、空source | DBへ不正値を残さない |
| Collectorからの独立性 | platformが一致するWindowsの`screenpipe`、Androidの別source識別子を直接Repositoryへ渡す | sourceの汎用文字列制約を満たせば保存でき、既知sourceの許可リストや固定対応を要求しない |
| トランザクション | 複数件の途中で保存失敗 | 今回のカテゴリ・端末・アプリ・Sessionの部分保存がない |
| JST境界 | 00:00の開始・終了、UTCでは前日となる時刻 | §6の半開区間どおりに抽出される |
| 日跨ぎ | 前日開始・当日終了、全日を覆う長いSession | 元区間を保持し、Backendが正しい`display`の区間・当日分時間・継続フラグを返す |
| 日末の表示 | 翌日00:00ちょうどに終了するSessionと、翌日へ継続するSession | 両方で`endsAtDayBoundary = true`、`continuesToNextDay`は前者false・後者true |
| DST | `America/New_York`の2026-03-08 / 2026-11-01 | UTC範囲がそれぞれ23時間 / 25時間になり、境界データを正しく抽出する |
| 正規化 | offset付きdatetime、timezoneなしdatetime | 前者はUTCへ正規化、後者はエラー |
| 並び順 | 同じ開始時刻を持つ複数記録 | 取得を繰り返しても順序が同じ |
| 統計の基準値 | §8の独立した4件fixture | Android 2,100,000 ms・3件、Windows 1,800,000 ms・1件、合計3,900,000 ms・4件・2アプリ |
| 統計の区間 | 日跨ぎ・DST・複数日を跨ぐ1件 | 境界で切り詰めた時間を集計し、期間集計の件数はIDごとに1件 |
| 統計の精度・順序 | ミリ秒を含む時間、同じ合計時間のアプリ | 秒へ丸めず、`usageMs`降順・`appId`昇順 |
| Viewの一致 | 同じ日付・zoneの両API | `display.durationMs`の合計・件数・アプリ数がStatisticsと一致する |
| 原本の独立性 | 両APIを繰り返し取得、テスト内でMaster名称を更新して再取得 | GETでDBが変化せず、Master変更は両Viewに反映されてもFactの区間・durationは変わらない |
| API正常系 | 通常日、空日、timezone省略、未使用アプリMasterあり | 契約どおりの200、空配列・統計0、既定zone。未使用アプリを統計へ含めない |
| API異常系 | 日付欠落・無効日付・無効zone・`from >= to`・DB例外 | 両APIで規定の422 / 500と安全なエラー形式 |
| 全件性 | テスト用の1日10,000件 | 脱落・重複がなく、黙って打ち切られない |
| Migration | 空DBからhead、headへ再実行、使い捨てDBでdowngrade → upgrade | テーブル・制約・indexが期待どおり。初回downgradeによるデータ保持は期待しない |

Session生成・Roomへ保存してから同じIDで再送する動作・同期POST・写真・位置・ActivityWatch変換は対応フェーズでテストする。source固有のplatform保証はCollector / Adapter側のテストとし、Phase 1のRepositoryテストでは汎用制約とsource追加への独立性を検証する。

### 11.2 Frontend

Vitest + React Testing Libraryで、画面上の挙動を検証する。

- 時刻・端末・アプリ名、identifierを使う表示名と、APIの計算済み`display`をそのまま用いる表示。
- 元区間と`display`が異なる日跨ぎレスポンスを渡し、`display.durationMs`と継続フラグで描画すること。Frontend用の日跨ぎ計算関数・計算テストは作らない。
- `endsAtDayBoundary`による`24:00`表示と、`continuesToNextDay`による継続ラベルを別々に確認する。
- Dashboardの合計・アプリ別表・platform表示、整数ミリ秒からの表示変換、0件時の値。
- 日付入力、前日・翌日・今日、URL保持、戻る/進む。
- JSTと別タイムゾーン、月末・年末・うるう日を跨ぐ日付操作。
- 初回loading、0件、API失敗、JSON以外の失敗応答、再試行。
- Timelineだけ成功 / Statisticsだけ成功の場合の部分エラーと該当APIの再試行。
- 遅い旧リクエストが新しい日付の一覧・統計を上書きしないこと。
- ラベルに基づく操作、長い名前でも操作要素が隠れないこと。

API clientをモックした画面テストに加え、実APIとの接続は次の代表E2Eで確認する。

### 11.3 結合・E2E

Playwrightで以下を代表フローとして検証し、全PRの必須チェックで実行する。テストごとの一時ファイルSQLiteへAlembicとseedで記録を保存し、同じDBを参照するFastAPIとReactを起動する。SQLite・Repository・APIレスポンスをモックせず、ブラウザから実APIを呼び出してReactのDOMを確認する。プロセスが準備完了したことを確認してからテストを開始する。

```text
一時DBにmigration・seed
  → Backend / Frontend起動
  → 2026-09-03・Asia/TokyoのURLを開く
  → Chromeの09:00〜09:20表示を確認
  → Dashboardのアプリ別時間・件数・合計をfixtureの期待値と照合
  → 前日へ切替、Backendが返す日跨ぎ区間・当日分時間・継続表示と統計を確認
  → 2026-09-05へ移動、空Timelineと統計0を確認
  → 日付を戻してreload、選択と両表示を確認
```

fixtureはDBへの投入にだけ使用し、Frontendへの固定データ供給やAPIの差し替えには使わない。これによりDB接続・Query・レスポンス変換・React描画のどこかが壊れるとE2Eが失敗する。永続化はBackendのDBテストとWindowsの再起動手順でも確認する。E2EへAndroid・Tailscale・地図を持ち込まない。

### 11.4 CIの実行ゲート

- **Backend:** Ruff、mypy、pytest、空SQLiteへのAlembic upgrade、revisionが単一headであることの確認。
- **Frontend:** ESLint、TypeScript型チェック、Vitest、production build。
- **Windows固有の確認:** 少なくともBackendのmigration・永続化・IANAタイムゾーン解決をWindows runnerで実施する。
- **E2E:** `pull_request`で毎回`pc-core-e2e`を実行し、mainへのmergeに必須とする。ドキュメントのみのPRも含め、path filter等で省略しない。実DB → API → Reactの代表シナリオを少なくともChromiumで実行し、テスト0件・skip・`continue-on-error`で成功扱いにしない。mainへのmerge後の実行は補助確認として追加できるが、PRでの実行の代わりにはしない。
- **Android:** Phase 0の既存CIを維持する。Phase 1でAndroid機能テストを追加しない。

実行時間が長い場合は依存キャッシュ等で短縮するが、migration・正規化・日付境界・重複防止・集計整合性・実DB接続E2EはPRから外さない。CI設定だけで完了とせず、保護ルール / rulesetの必須チェック指定と、失敗したPRをmergeできないことも確認する。大量データの速度は環境と併せて記録し、未計測の性能値を達成済みとはしない。

## 12. Windowsでの動作確認と完了条件

### 12.1 READMEに載せる操作手順

以下は実装後に成立させるコマンド形の案。依存導入・仮想環境・Frontendのpackage managerはPhase 0で確定した手順を記載する。

1. Backend / Frontendの依存をlockファイルに従って導入する。
2. リポジトリルートのPowerShellでデモ用保存先を絶対パスとして設定する。

   ```powershell
   $env:LIFE_TIMELINE_DATA_DIR = Join-Path (Get-Location).Path 'data/demo'
   ```

3. 同じシェルで`backend/`へ移動し、migrationとseedを実行する。

   ```powershell
   alembic upgrade head
   python -m app.cli.seed --data-dir "$env:LIFE_TIMELINE_DATA_DIR"
   ```

4. 同じ環境設定でBackendを起動する。

   ```powershell
   uvicorn app.main:app --host 127.0.0.1 --port 8000
   ```

5. 別ターミナルの`frontend/`でViteをloopback限定・ポート5173固定で起動する。起動scriptは`strictPort`を有効にし、ポート使用中に別ポートへ黙って切り替えない。
6. `http://127.0.0.1:5173/timeline?date=2026-09-03&timezone=Asia%2FTokyo`を開く。
7. 固定日のTimelineとDashboardをfixtureの期待値へ照合し、日付切替・空日・日跨ぎが両方に反映されることを確認する。
8. seedを再実行し、Master / Factの件数と統計値が変わらないことを確認する。
9. Backendを停止・再起動し、同じ記録・統計が表示されることを確認する。別シェルを使う場合は同じ絶対パスを設定し直す。
10. Backend停止中に日付を変え、両領域の取得失敗を確認する。再起動して再試行し、復旧を確認する。

DBパス、migration未適用、ポート競合、タイムゾーン不正、Backend停止をREADMEの切り分け項目に含める。

### 12.2 受け入れチェックリスト

| ID | 完了条件 | 主な証拠 |
| --- | --- | --- |
| AC-01 | 手順だけで4テーブルの正規化schemaを初期化し、Android / Windowsの共通Sessionを保存できる | 空DBのmigration・Master / Factの制約テスト、Windows起動記録 |
| AC-02 | ダミーデータを保存し、Backend再起動後も保持する | DB再接続テスト、再起動による手動確認 |
| AC-03 | LifeTimeline AppSession IDを保存・取得・再投入で維持し、二重登録・既存履歴の上書きがない | ID保持・冪等性・競合・rollbackテスト |
| AC-04 | 指定日のアプリ利用区間がAPIから安定した順で返る | API正常系・境界・順序テスト |
| AC-05 | Backendだけで日跨ぎの区間・当日分時間・継続判定を計算し、Reactは計算済み`display`を描画する | JST / DST・日末フラグ・集計のBackendテスト、表示用レスポンスの描画テスト |
| AC-06 | Reactでアプリ名・端末・時刻・当日分使用時間を読める | 画面テスト、実APIを使うE2E |
| AC-07 | 日付変更でTimelineと統計が切り替わり、reloadでも選択が残る | 日付操作・URLテスト、E2E |
| AC-08 | 両表示で0件と取得失敗を区別し、片方の失敗も再試行で復旧できる | 空・部分errorテスト、Backend停止/復旧確認 |
| AC-09 | FastAPI / Viteがloopbackだけで待ち受ける | 起動設定とWindowsの待受確認 |
| AC-10 | lint・型チェック・unit/API/DBテスト・migration・Frontend buildと、全PR必須の実DB → API → React E2Eが成功する | CI結果、`pc-core-e2e`の必須チェック設定とmerge制御確認 |
| AC-11 | 原本を直接集計するStatistics APIと最小Dashboardで、アプリ別時間・件数・合計を確認できる | 基準値・複数端末・同名アプリの集計テスト、E2E |
| AC-12 | 同じ原本・日付・zoneでTimelineの当日分と統計が一致し、Viewを取得しても原本が変わらない | API間整合性・読み取り時不変性のテスト |
| AC-13 | Repositoryはdeviceとappのplatform一致を保証し、source固有の固定対応に依存しない | platform不一致の拒否・別sourceの受け入れテスト |

AC-01〜13とPR必須E2Eの完了をもってPhase 1を完了とする。これは全体MVPの完成ではなく、Android実機からの収集・同期に関するMVP受け入れ条件はPhase 2以降に残る。

## 13. リスクと後続フェーズへの引き継ぎ

| リスク・論点 | Phase 1での対処 | 引き継ぎ |
| --- | --- | --- |
| Phase 0未実施のまま着手する | 開始条件を確認し、雛形・CIを先に整える | Androidの基盤はPhase 0で完了させる |
| seedとAPIが別のDBを開く | 共通の絶対パス解決と実行手順で固定 | 同期受信・Backupにも同じ設定を適用する |
| UTCの暦日で検索して日本時間の深夜が欠落する | timezoneからUTC境界を算出し、境界・DSTをテストする | Androidのイベント時刻もUTCで正規化する |
| 日跨ぎSessionの総時間を当日分と誤表示・誤集計する | 原本を保持し、Backendが共通の計算で`display`と統計を生成する。Frontendは整形だけ行う | 日別件数と期間全体の件数の意味も維持する |
| Timeline専用形式や日次集計が原本になる | Master / Factを保存し、Timelineと統計をそれぞれQueryで生成する | Aggregate導入時も再生成可能なCacheに限る |
| 複数端末で同じアプリが重複Masterになる | `apps(platform, identifier)`の一意性とID解決 | Android側のアプリ情報からPCの`app_id`へ解決する同期契約をPhase 2で定義する |
| 再投入で履歴が増える・変わる | Sessionの単独IDと端末・内容の照合、固定fixture | 同期ACK・再送時の更新規約をPhase 2で確定する |
| 生イベントIDとの混同・同期時のID再生成 | IDをLifeTimelineの正規化AppSession IDとして定義し、受け取った値を保持する | Phase 2でSession生成時に一度付与し、Room保存後に同じIDで同期・再送する |
| 新Collector追加のたびに保存層を修正する | Repositoryは汎用制約とdevice / appのplatform一致だけを扱い、sourceの固定対応を持たない | Collector / Adapterでsource固有の検証を行う |
| Unit Testは通るがDBと画面が接続されていない | モックを使わない実DB → API → React E2Eを全PRで必須にする | フェーズ追加時も必須チェックを維持し、シナリオを拡充する |
| 同時利用を人の実活動時間と誤解する | 記録されたSession時間の合計として表示する | 重複区間の統合やAFK控除は別指標として設計する |
| Windowsでタイムゾーン解決ができない | tzdataを依存管理し、Windows runnerで検証する | 実機のタイムゾーン変更時の運用はPhase 2以降で確認する |
| SQLiteへ同時書き込みが増える | 短いトランザクションとtimeout、失敗時rollback | 同期導入時にWAL・競合・再試行方針を実測して判断する |
| 種類追加のための過剰な先行実装 | Phase 1のMaster / Factと2つのQueryを実装する | `media_items`・位置情報・`desktop_session_details`・`manual_records`は対応Phaseで追加する |
| 閲覧中に古い日付の応答が到着する | 両APIでリクエスト中断または応答識別を行う | Filter追加時も検索条件全体で応答を対応付ける |

Phase 2開始時には、LifeTimelineがSession生成時に一度だけ付与するAppSession ID、ID付きSessionのRoom保存完了後の同期、再送時の同じIDの再利用を必須の前提として引き継ぐ。単独IDの一意性と端末照合、`apps`の自然キーとMaster解決、`*_at_ms`・`duration_ms`、汎用的な`source`、Session区間の規約も共有する。sourceとplatformの取得元固有の保証はCollector / Adapterへ置く。送信payloadは正規化保存へ変換できる形で別途定義し、Timelineの`display`やStatisticsなどの表示用レスポンスを同期形式として流用しない。端末登録、UsageStatsからのSession確定と再処理時の照合、ACK、同じIDへの訂正、Master更新、エラーと再送を個別に設計する。

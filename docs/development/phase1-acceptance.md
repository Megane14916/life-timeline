# Phase 1受け入れ記録

## 1. 判定

Phase 1のPC Core受け入れ条件AC-01〜13を、Windowsの一時SQLite検証、既存Backend/Frontendテスト、`pc-core-e2e`、CI、mainのrulesetで確認した。Phase 2へ残す同期・ACK・再送・Android Collectorの実装は、Phase 1の未達ではなく計画どおりの後続作業として扱う。

検証日は2026年9月9日。対象はP1-07を含む`main`（commit `f973a8c`）と、P1-08の作業ブランチである。

## 2. Windows検証環境

Phase 0受け入れ記録のclean checkout検証で固定toolchainの導入を確認済みである。P1-08では同じversion方針を使い、BackendのPython仮想環境から実行した。

| 項目          | 基準値            |
| ------------- | ----------------- |
| OS            | Microsoft Windows |
| Python        | 3.13.15           |
| uv            | 0.12.9            |
| Node.js       | 24.20.0           |
| npm           | 11.19.0           |
| JDK           | 17                |
| Backend bind  | `127.0.0.1:8000`  |
| Frontend bind | `127.0.0.1:5173`  |

## 3. 実測結果

### 3.1 一時DB、migration、seed

既存の個人DBを使わず、`%TEMP%`配下の新しい絶対パスを`LIFE_TIMELINE_DATA_DIR`へ設定した。

```powershell
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-p1-08-<unique>'
uv run alembic upgrade head
uv run python -m app.cli.seed --data-dir $env:LIFE_TIMELINE_DATA_DIR
uv run python -m app.cli.seed --data-dir $env:LIFE_TIMELINE_DATA_DIR
```

実測結果:

- 初回migrationで`0001_initial_normalized_schema`を適用。
- 初回seedはcategories 2、devices 3、apps 5、app_sessions 10をinsert。
- 2回目seedは全Master/Factでinsert 0、existingがfixture件数となり、重複しなかった。
- DBは指定した絶対パスの`lifelog.db`に作成された。

### 3.2 Backend / Frontend接続

同じ環境変数を継承したBackendと、loopback固定・strict portのFrontendを起動した。

```powershell
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
npm run dev
```

実測結果:

- `GET /api/v1/health`は`{"status":"ok"}`。
- `http://127.0.0.1:5173/`はHTTP 200。
- `netstat -ano`で両方のLISTENINGアドレスが`127.0.0.1`だった。
- 固定URLのTimelineは4件、Statisticsはusage 3,900,000ms、session 4、app 2を返した。

### 3.3 再起動、停止、復旧

- Backend停止中のhealth requestは失敗した。
- 同じ`LIFE_TIMELINE_DATA_DIR`でBackendを再起動するとhealthは`ok`へ戻った。
- 再起動後もStatisticsのusageは3,900,000msで、DBの記録・集計値は変わらなかった。
- ブラウザE2EではDashboardの一時失敗、再試行、実APIからの復旧を確認した。

### 3.4 固定日の表示確認

| URLの日付    | Timeline                                  | Dashboard                                                    |
| ------------ | ----------------------------------------- | ------------------------------------------------------------ |
| `2026-09-03` | 4件。日跨ぎChromeは10分、「前日から継続」 | 1時間5分、4件、2件。Android Chrome 35分、Windows Chrome 30分 |
| `2026-09-04` | 3件                                       | 1分30秒                                                      |
| `2026-09-05` | 空状態                                    | 0分、0件、0件                                                |

`2026-09-03`から翌日・空日へ切り替え、元の日付へ戻してreloadしても、URL、Timeline、Dashboardの日付が一致することを`pc-core-e2e`で確認した。

## 4. CIとmerge制御

P1-07の検証PR [#27](https://github.com/Megane14916/life-timeline/pull/27)で次の4 workflowが成功した。

- [Frontend CI](https://github.com/Megane14916/life-timeline/actions/runs/34307809906)
- [Backend CI](https://github.com/Megane14916/life-timeline/actions/runs/34307809908)
- [Android CI](https://github.com/Megane14916/life-timeline/actions/runs/34307809886)
- [PC Core E2E](https://github.com/Megane14916/life-timeline/actions/runs/34307810005)

mainの[保護ruleset](https://github.com/Megane14916/life-timeline/rules/22253391)はPull Requestとbranch更新を要求し、`frontend-ci`、`backend-ci (ubuntu)`、`backend-ci (windows)`、`android-ci`、`pc-core-e2e`をrequired status checkとして扱う。失敗・未実行・中断したcheckをmerge成功とは扱わない。

## 5. AC-01〜13

| ID    | 結果 | 証拠                                                                                 |
| ----- | ---- | ------------------------------------------------------------------------------------ |
| AC-01 | PASS | Windowsの一時絶対パスへ4テーブルをmigrationし、Android / Windowsのseed Sessionを保存 |
| AC-02 | PASS | seed再実行がinsert 0、Backend再起動後も同じ統計値                                    |
| AC-03 | PASS | 固定AppSession ID、再投入時のexisting、競合・rollbackをBackend testで確認            |
| AC-04 | PASS | Timeline APIの日付境界・順序・4件を確認                                              |
| AC-05 | PASS | 日跨ぎの当日分時間と継続表示をBackend API / E2Eで確認                                |
| AC-06 | PASS | 実DB APIからReactへアプリ名・端末・時刻・時間を描画                                  |
| AC-07 | PASS | 日付切替、URL同期、空日、reloadをE2Eで確認                                           |
| AC-08 | PASS | 空状態、Dashboard部分失敗、再試行復旧、Backend停止復旧を確認                         |
| AC-09 | PASS | FastAPI `127.0.0.1:8000`、Vite `127.0.0.1:5173`をWindowsで確認                       |
| AC-10 | PASS | lint・型チェック・unit/API/DB・migration・build、required `pc-core-e2e`をCIで確認    |
| AC-11 | PASS | Statistics APIのアプリ別時間・件数・合計とDashboardを照合                            |
| AC-12 | PASS | 同じ原本・日付・Asia/TokyoでTimelineとStatisticsの当日分が一致                       |
| AC-13 | PASS | device/appのplatform整合性をRepository testで確認                                    |

## 6. トラブルシュートと設計上の整理

- 保存先は`LIFE_TIMELINE_DATA_DIR`の絶対パスで統一し、seedとBackendが別DBを開かないようにした。
- migration前seed、DBパス誤り、8000/5173のポート競合、timezone不正、Backend停止はREADMEに切り分け手順を記載した。
- Phase 1のFactは`app_sessions`であり、`app_sessions.id`単独を主キーとして扱う。`desktop_sessions`は上位文書に残る旧称で、ActivityWatch固有の実装はPhase 6へ残す。
- Timeline APIは`/api/v1/timeline`、Statistics APIは`/api/v1/stats/apps`を正とし、表示用レスポンスを同期payloadとして扱わない。

## 7. Phase 2への引き継ぎ

- Android UsageStatsから確定済みAppSessionを生成し、同じIDでRoomへ保存する。
- Sync APIのACK、再送、重複防止、訂正、再処理時の照合規約を定義する。
- Tailscale接続、WorkManager retry、pending/syncing/syncedはPhase 2以降で実装する。
- ActivityWatch、`desktop_session_details`、写真、位置情報、検索、Export、Backupは各計画Phaseで追加する。

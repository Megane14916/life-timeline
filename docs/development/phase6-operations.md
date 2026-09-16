# Phase 6 Windows運用手順

この文書は、ActivityWatchとlife-timelineをWindowsで日常運用するときの起動、停止、backfill、privacy変更、backup、障害切り分けをまとめたものです。実機受け入れの操作と結果は[Phase 6 Windows実機受け入れ手順・記録](phase6-acceptance.md)へ記録します。

## 1. 固定する境界

| 項目 | 現行値 |
| --- | --- |
| ActivityWatch | 公式stable `v0.13.2` |
| ActivityWatch接続 | `http://127.0.0.1:5600`、read-only GET |
| Backend bind | `127.0.0.1:8000` |
| Frontend開発bind | `127.0.0.1:5173` |
| default | ActivityWatch連携OFF、privacy mode `app_only` |
| 自動import | 起動後30秒、通常15分間隔 |
| retry | 一時エラー時1〜15分backoff |
| 初回自動範囲 | 7 UTC日 |
| CLI backfill | 最大31 local日、`--to`はexclusive |
| 1 runの上限 | 最大8 UTC日または8分 |
| backup | `LIFE_TIMELINE_DATA_DIR`全体（`lifelog.db`と`thumbnails/`を含む） |

ActivityWatchは外部のsourceであり、life-timelineは原本を削除・変更しません。ActivityWatch本体のDBやraw exportをlife-timelineのdata root、repository、Issue、PR、CI artifactへコピーしないでください。

## 2. 日常の起動

### 2.1 ActivityWatch

1. ActivityWatch公式installerのstable `v0.13.2`を使用する。
2. ActivityWatch本体、window watcher、AFK watcherを起動する。
3. Web詳細を使う日だけ、専用browser profileへWeb watcher / extensionを導入する。
4. loopbackでserverが起動していることを確認する。レスポンス本文やbucket一覧をファイルへ保存しない。

ActivityWatchのserverが利用できないときも、life-timelineの既存Timeline、Photos、Mapは利用できます。停止中に発生した新しいPC記録は、ActivityWatchが復旧するまで取り込まれません。

### 2.2 BackendとFrontend

Backend用PowerShellで、毎回同じdata rootを明示します。既定OFFのまま起動するとActivityWatchへ接続しません。

```powershell
cd backend
$env:LIFE_TIMELINE_DATA_DIR = '<保護されたdata rootの絶対パス>'
$env:LIFE_TIMELINE_ACTIVITYWATCH_ENABLED = 'true'
$env:LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE = 'app_only'
uv run alembic upgrade head
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

migrationはdata rootの初回作成時と、アプリ更新後に同じ保存先へ適用します。別のPowerShellから、実データを出力せずstatus codeだけ確認します。

```powershell
$response = Invoke-WebRequest 'http://127.0.0.1:8000/api/v1/health'
if ($response.StatusCode -ne 200) { throw 'Backend health failed.' }
$activityWatch = Invoke-WebRequest 'http://127.0.0.1:5600/api/0/info'
if ($activityWatch.StatusCode -ne 200) { throw 'ActivityWatch is unavailable.' }
```

Frontendは別のPowerShellで起動します。

```powershell
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

Androidから接続する場合は、[Phase 2 Tailscale Serve接続手順](phase2-tailscale.md)を使用します。FastAPIをLAN addressや`0.0.0.0`へbindせず、Funnelやcleartext HTTPへ変更しません。

## 3. statusと手動import

FrontendのActivityWatch panelでは、`連携`、`Privacy mode`、`最終結果`、`最終成功`、`取込済み期間`、`次回予定`、`Web詳細`だけを確認します。hostname、bucket ID、app名、title、URL、例外本文は記録しません。

状態確認をAPIで行う場合も、値をログへ保存せず画面で一時的に確認します。

```powershell
Invoke-RestMethod 'http://127.0.0.1:8000/api/v1/activitywatch/status' |
  Select-Object enabled, detailMode, state, lastResult, lastAttemptAt, lastSuccessAt, completedThrough, nextAttemptAt, webDetailsAvailable
```

「今すぐ取り込む」は同時に1件だけ実行します。連続clickや別PowerShellからの同時CLI実行は避け、実行中はstatusが完了するまで待ちます。

## 4. backfillと再取込

初回自動importは直近7 UTC日だけです。古い履歴は31日以下の範囲へ分けてCLIでbackfillします。`<from>`、`<to>`、timezoneは利用者の対象範囲へ置き換えますが、実値をこの文書やIssueへ書きません。

まずdry-runで取得・正規化だけを確認します。

```powershell
cd backend
$env:LIFE_TIMELINE_DATA_DIR = '<保護されたdata rootの絶対パス>'
uv run python -m app.cli.import_activitywatch `
  --from <from> --to <to> --timezone UTC --privacy-mode app_only --dry-run
```

問題がなければ同じrangeを通常runで実行します。

```powershell
uv run python -m app.cli.import_activitywatch `
  --from <from> --to <to> --timezone UTC --privacy-mode app_only
```

CLI出力はchunk数、Session数、duration、invalid、redacted、truncated、dry-run、partialなどの集計値だけです。hostname、bucket ID、event ID、app名、title、URLが出力された場合は運用を止め、ログを共有せずに実装の不具合として切り分けます。

同じrangeを再実行した後は、TimelineのSession数、ID、PC利用時間、Dashboardのplatform totalが変わらないことを画面で確認します。再取込は不足期間を埋める操作であり、ActivityWatch側のraw eventやlife-timelineの全履歴を消す操作ではありません。

## 5. privacy modeの変更

privacy modeは画面表示だけのfilterではなく、SQLiteへdetailを書き込む前のdata minimizationです。

| mode | 保存されるdetail |
| --- | --- |
| `app_only` | title / URLなし |
| `titles` | 非browser window titleのみ。browserのWeb detailなし |
| `web` | browserの許可されたtitle / URL。query、fragment、userinfo、incognito detailは保存しない |

変更手順は次のとおりです。

1. Backendを停止する。
2. 同じdata rootで`LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE`を変更して再起動する。
3. 変更対象rangeを`--privacy-mode <new-mode>`付きでCLI再生成する。
4. Timelineをreloadし、対象rangeのdetailが新しいmodeに従うことを確認する。

例として、過去のdetailを狭めるときは次を実行します。

```powershell
$env:LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE = 'app_only'
uv run python -m app.cli.import_activitywatch `
  --from <from> --to <to> --timezone UTC --privacy-mode app_only
```

modeを狭めても対象range外のdetailは自動削除されません。必要な期間を明示して再生成します。URLのpath自体に秘密情報が含まれる場合があるため、`web` modeでは専用localhost pageだけを使用します。

## 6. 停止、復旧、起動順序

### 6.1 通常停止

1. Androidの自動同期が動作中でないことを確認する。
2. Frontendを終了する。
3. Backendを`Ctrl+C`で終了する。
4. ActivityWatch本体とwatcherを終了する。
5. Tailscale Serveを使用していた場合は、検証終了時だけServeをdisableする。

Backend停止中もAndroidのpendingとActivityWatch側のsourceは消えません。専用環境以外でdata rootを削除・移動しないでください。

### 6.2 Backend停止からの復旧

Backend停止中もActivityWatchを動かしていた場合、同じdata rootでBackendを起動すると起動後scheduleまたは手動importでcatch-upします。復旧時に別名の空data rootを指定しないでください。

確認順序:

1. ActivityWatchが起動していることを確認する。
2. Backendを同じdata root、同じloopback bindで起動する。
3. healthが正常であることを確認する。
4. statusの成功相当を待つ。必要な場合だけ手動importを1回実行する。
5. 追加Sessionと既存Sessionの重複がないことをTimelineで確認する。

### 6.3 ActivityWatch停止からの復旧

ActivityWatch停止時はlife-timelineのhealth、既存Timeline、Photos、Mapを利用できます。statusは`unavailable`または要確認の状態になります。

1. ActivityWatchを再起動する。
2. window / AFK watcherを起動し、Web確認時だけWeb watcherを起動する。
3. statusのretryまたは手動importを1回実行する。
4. success相当になった後、既存Sessionの重複がないことを確認する。

### 6.4 起動順序が逆になった場合

Backendを先に起動してActivityWatchが後から起動した場合、最初の接続は`unavailable`になります。ActivityWatch復旧後のscheduler retryまたは手動importで再開します。ActivityWatchが停止したままでも、Backendを再起動してhealthを回復させる必要はありません。

## 7. Backupと復旧

life-timelineには自動backup機能がありません。`lifelog.db`にはPC利用履歴、Android履歴、Photo metadata、LocationPoint、PlaceVisitが含まれます。写真を使用している場合、thumbnailも同じdata rootの`thumbnails/`に保存されるため、DBとthumbnailを一体で扱います。

### 7.1 backup

Backendを停止した後、data root全体を別の保護された保存先へコピーします。実パスはshell historyやrepositoryへ残さない運用にしてください。

```powershell
if (-not $env:LIFE_TIMELINE_DATA_DIR) { throw 'Set LIFE_TIMELINE_DATA_DIR first.' }
$source = (Resolve-Path $env:LIFE_TIMELINE_DATA_DIR).Path
$backupRoot = Join-Path $env:USERPROFILE 'Documents\life-timeline-backups'
New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
$backupPath = Join-Path $backupRoot ('life-timeline-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
Copy-Item -LiteralPath $source -Destination $backupPath -Recurse
if (-not (Test-Path (Join-Path $backupPath 'lifelog.db'))) { throw 'lifelog.db is missing.' }
```

backupに含める対象はdata root全体です。ActivityWatch本体のraw DBを別途扱う場合も、life-timelineのrepositoryや受け入れ記録へコピーしません。

### 7.2 上書きしない復旧確認

既存data rootを直接上書きせず、まずbackupから別の専用復旧rootへコピーして検証します。

```powershell
$restoreRoot = Join-Path $env:TEMP 'life-timeline-phase6-restore-<unique>'
New-Item -ItemType Directory -Force -Path $restoreRoot | Out-Null
Copy-Item -LiteralPath '<backup directoryの絶対パス>\*' -Destination $restoreRoot -Recurse
$env:LIFE_TIMELINE_DATA_DIR = $restoreRoot
```

復旧rootでBackendを起動し、health、Timeline、Photos、Map、Windows / Androidの既存表示を確認します。DBだけ、または`thumbnails/`だけを復旧して完全復旧と判断しません。復旧確認後に通常rootへ戻す場合は、全プロセス停止と対象rootの再確認を行ってから利用者が手動で判断します。

## 8. 障害切り分け

| 症状 | 最初に見る項目 | 次の操作 | 記録してよい内容 |
| --- | --- | --- | --- |
| Backend healthが失敗 | bind、port、data root、migration | 同じrootで起動引数を修正 | `health失敗`、OS major version |
| ActivityWatch unavailable | ActivityWatch server / watcherの起動 | ActivityWatch復旧後にretry | `unavailable`、復旧したか |
| Sessionが増えない | status、privacy mode、AFK watcher | 専用アプリで短時間操作し、必要なら手動import | 表示あり / なし、result code |
| AFK時間が加算される | AFK watcherと対象時間 | watcher状態を確認し再import | AFK除外の成否 |
| title / URLがない | mode、browser mapping、Web watcher、incognito | 専用localhost pageでmodeを確認 | detailあり / null |
| 同じSessionが重複したように見える | 日付、timezone、Timeline reload | DBを直接編集せず同一rangeを確認 | 重複あり / なし |
| Android pendingが残る | Tailscale、Serve、PC health | Phase 2の接続手順で復旧 | 自動 / 手動 / 未復旧 |
| migration error | 対象rootとschema head | 同じrootへ`alembic upgrade head` | migration成功 / 失敗 |

ログを共有して原因を説明しようとせず、最初に画面の安全なresult code、HTTP status class、件数、復旧可否だけを確認します。raw log、exception本文、URL、hostname、bucket ID、app一覧、DBをIssue / PRへ貼り付けません。

## 9. 非保証

- 自動scheduleはdeadlineではなく、PC sleep、負荷、通信、ActivityWatch設定、retry backoffで遅延します。
- ActivityWatch停止中やwatcher権限不足の期間をlife-timelineが復元することはできません。
- PC利用時間はwindowと`not-afk`の重なる記録時間であり、Windows uptime、勤務時間、請求時間、集中時間を保証しません。
- ActivityWatch version差、browser mapping差、Web watcher未導入、incognito、sleep / resume、timezone変更の全組合せは保証対象外です。
- life-timelineからSessionを削除してもActivityWatch原本は削除されず、再取込で復元され得ます。
- privacy modeを変更した場合、指定rangeを再生成するまで過去detailは変わりません。

## 10. 参照

- [Phase 6 Windows実機受け入れ手順・記録](phase6-acceptance.md)
- [Phase 6 ActivityWatch privacy review](phase6-activitywatch-privacy.md)
- [Phase 6詳細計画](../detailed_plan/phase6-activitywatch.md) §11〜14
- [Phase 2 Tailscale Serve接続手順](phase2-tailscale.md)
- [Phase 5位置情報の運用手順](phase5-location-operations.md)
- [ActivityWatch releases](https://github.com/ActivityWatch/activitywatch/releases)

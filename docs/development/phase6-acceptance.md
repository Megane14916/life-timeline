# Phase 6 Windows実機受け入れ手順・記録

この文書は、P6-10（Issue #127）のWindows実機確認を行うための手順と、利用者が実施した結果を記録するためのテンプレートです。ActivityWatchのwatcher、AFK、browser extension、Backendの停止復旧、再取込、Androidとの共通Timelineを確認します。

このcheckoutでは実機操作を行っていないため、未実施項目をPASSへ変更しません。実施結果は、個人の履歴を含まない集計情報だけをこの文書へ記録してください。

## 1. 判定と対象範囲

| 対象 | この文書での扱い |
| --- | --- |
| Backend / Frontend / Androidのunit・integration・CI | 自動検証の結果として扱う。実機の代替にはしない |
| Windows ActivityWatch通常系 | 本文の手順を利用者が実施して記録する |
| Androidとの共通Timeline | 合成AppSessionまたはテスト用端末の記録を使って確認する |
| 7日以上の長期運転、全browser、sleepやtimezone変更の全組合せ | 任意確認。未実施はPASSにしない |

結果の値は次のいずれかに限定します。

- `PASS`: 前提を満たして操作し、期待状態を確認した。
- `FAIL`: 前提を満たして操作したが、期待状態にならなかった。症状カテゴリだけ記録する。
- `BLOCKED`: permission、ネットワーク、OS設定などの前提を満たせず実施できなかった。
- `NOT TESTED`: まだ実施していない。

## 2. Privacyと専用環境の必須条件

実機確認では通常利用のPC DBやbrowser profileを使わず、専用のWindowsユーザーまたは専用端末と、新しいPhase 6専用data rootを使用します。

- ActivityWatchは公式配布のstable `v0.13.2`を使用する。life-timelineへActivityWatch本体やraw exportをコピーしない。
- ActivityWatchは`127.0.0.1:5600`のloopbackだけで使用する。LAN bind、Funnel、任意のremote endpointは使用しない。
- Backendは`127.0.0.1:8000`だけでlistenする。Androidからの接続は既存のTailscale Serve HTTPS経路だけを使う。
- `app_only`を既定とし、`titles` / `web`は専用アプリ・専用localhost pageで必要な範囲だけ確認する。
- 合成対象にはWindows Calculator、Notepad、Frontendの`/phase6-synthetic.html`を使う。普段のアプリ、個人ページ、実在のURLは使わない。
- hostname、tailnet名、bucket ID、device ID、普段のapp一覧、window title、URL、token、DB、raw log、packet capture、スクリーンショットをIssue / PR / この文書へ記録しない。
- Web確認ではquery、fragment、userinfo、incognitoの値を記録しない。保存値がnullまたはquery / fragmentなしになることだけを確認する。
- Mapのonline basemapはOFFのままにする。位置情報の実機確認はPhase 5の受け入れ手順に従い、この文書のActivityWatch確認へ混ぜない。

## 3. 事前準備

### 3.1 PC側の専用data root

repository rootのPowerShellで、`<unique>`を自分だけが判別できる一時値へ置き換えて実行します。実際の絶対パスはこの文書やIssueへ貼り付けません。

```powershell
cd backend
uv sync --all-groups --frozen
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-phase6-acceptance-<unique>'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run python -m app.cli.seed --data-dir $env:LIFE_TIMELINE_DATA_DIR
```

seedは専用DBにPhase 5までの合成データを入れるために1回だけ実行します。migrationとseedとBackendが同じ`LIFE_TIMELINE_DATA_DIR`を参照していることを確認します。

### 3.2 ActivityWatchとBackendの起動

ActivityWatchは公式installerでインストールし、ActivityWatchのserver、`aw-watcher-window`、`aw-watcher-afk`を起動します。実際のhostnameやbucket一覧をファイルへ保存せず、serverが起動していることだけを画面で確認します。Web確認を行うときだけ、専用browser profileへ対応するWeb watcher / extensionを追加します。

Backendを別のPowerShellで起動します。既定の受け入れは`app_only`です。

```powershell
cd backend
$env:LIFE_TIMELINE_DATA_DIR = '<専用data rootの絶対パス>'
$env:LIFE_TIMELINE_ACTIVITYWATCH_ENABLED = 'true'
$env:LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE = 'app_only'
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

別のPowerShellから、bodyやhostnameを出力せずにhealthのstatus codeだけを確認します。

```powershell
$health = Invoke-WebRequest 'http://127.0.0.1:8000/api/v1/health'
if ($health.StatusCode -ne 200) { throw 'Backend health check failed.' }
$aw = Invoke-WebRequest 'http://127.0.0.1:5600/api/0/info'
if ($aw.StatusCode -ne 200) { throw 'ActivityWatch health check failed.' }
Write-Output 'loopback services are reachable'
```

Frontendは別のPowerShellで起動します。

```powershell
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 5173
```

PCだけを確認する場合は`http://127.0.0.1:5173`を使用します。Androidから確認する場合は、[Phase 2 Tailscale Serve接続手順](phase2-tailscale.md)に従ってServeを設定し、実URLをrepositoryや記録へ残さず端末のPC endpointへ入力します。

### 3.3 Androidの準備（共通Timeline確認を行う場合）

既存のPhase 5専用端末または検証用Androidを使用し、debug APKをinstallします。Usage Access、PC endpoint、必要な同期設定はPhase 2 / 5の手順に従います。Android側の実際のdevice名、Session内容、URL、tokenは記録しません。

```powershell
.\android\gradlew.bat -p android assembleDebug
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

## 4. 実機で確認する通常系シナリオ

### AW-01: `app_only`のPC AppSession

1. Backend、Frontend、ActivityWatchを起動し、ActivityWatchのprivacy modeを`app_only`としておく。
2. CalculatorまたはNotepadを短時間操作し、別の短いAFK期間を作る。
3. BackendのActivityWatch status画面で連携が有効、最終結果が成功相当になることを確認する。起動直後は初回schedule待ちになるため、必要なら「今すぐ取り込む」を1回押す。
4. 操作した日付のTimelineを開き、Windows、専用端末名ではない安全な表示名、AppSession、利用時間が表示されることを確認する。
5. 詳細欄にwindow titleやURLが表示されないことを確認する。

期待結果: PCのAppSessionがAndroidのAppSessionと同じ共通Timelineへ入り、`app_only`で詳細情報が複製されない。

### AW-02: AFK除外

1. 合成アプリを操作してから、keyboard / mouseを操作しない時間を作る。
2. ActivityWatchのAFK判定が成立するまで待ち、Backendで取り込みを実行する。
3. Timeline / DashboardのPC利用時間を確認し、AFK期間がPC利用時間へ加算されていないことを確認する。

期待結果: Windows利用時間はwindow event全体やWindows uptimeではなく、windowと`not-afk`の重なる期間だけになる。AFK watcher停止中など判定できない場合はPASSにしない。

### AW-03: WindowsとAndroidの同一Timeline

1. 専用Androidで合成AppSessionを1件作り、PCへ同期する。
2. AW-01のWindows AppSessionと同じ日付・timezoneのTimelineを開く。
3. AndroidとWindowsの行が同じTimelineへ表示され、platform表示とdurationが区別されることを確認する。
4. DashboardのAndroid / Windows別合計が表示され、0件側も誤ってPC利用時間へ加算されないことを確認する。

期待結果: AndroidのAppSessionとWindowsのAppSessionが別端末・別platformとして保持され、表示日とtimezoneが一致する。

### AW-04: Backend停止中のActivityWatch記録と起動時catch-up

1. ActivityWatchとwatcherを起動したまま、BackendのPowerShellで`Ctrl+C`を押してBackendだけ停止する。
2. Backend停止中に専用アプリを短時間操作し、Backendが停止しているため新しいimportが保存されない状態を確認する。
3. 同じ`LIFE_TIMELINE_DATA_DIR`を指定してBackendを再起動する。
4. 起動後のinitial delayまたは「今すぐ取り込む」でcatch-upを待ち、statusが成功相当になることを確認する。
5. 追加された行がTimelineへ表示されることを確認する。

期待結果: Backend停止中の履歴はActivityWatch側に残り、Backend再起動後のcatch-upで取り込まれる。別の空DBを作らない。

### AW-05: ActivityWatch停止・retry復旧

1. 既存Sessionが表示される状態でActivityWatchのserverまたはwatcherを停止する。
2. Backendのhealth、既存Timeline、Photos、Mapが利用できることを確認する。ActivityWatch停止だけでBackend全体をunhealthy扱いにしない。
3. ActivityWatchを再起動し、statusのretryまたは「今すぐ取り込む」を1回だけ実行する。
4. 復旧後の新しいSessionが表示され、既存Sessionが二重化していないことを確認する。

期待結果: ActivityWatch停止中は安全な`unavailable`相当の状態になり、既存データは維持される。復旧後に取り込みが再開する。

### AW-06: manual triggerの重複防止

1. Frontendの「今すぐ取り込む」を連続して2回操作する。
2. ボタンが実行中表示になり、runが1つだけ受理されることを確認する。
3. 完了後にTimelineのSession件数とPC利用時間を確認する。

期待結果: 二重clickで同時runや重複Sessionが発生しない。別run中の要求は拒否または待機として安全に扱われる。

### AW-07: CLI再取込の冪等性

1. 対象日を1日または数日に限定し、同じrangeでdry-runを実行する。`<from>`と`<to>`は利用者が選び、実値を記録しない。

```powershell
cd backend
$env:LIFE_TIMELINE_DATA_DIR = '<専用data rootの絶対パス>'
uv run python -m app.cli.import_activitywatch --from <from> --to <to> --timezone UTC --dry-run
```

2. dry-runでDBやTimelineが変わらないことを確認する。
3. 同じrangeを通常runで2回実行する。
4. 各runのCLI出力は`chunks`、`sessions`、`duration_ms`、`invalid`、`redacted`、`truncated`、`partial`などの集計値だけを比較する。
5. Timelineのitem数、ID、PC利用時間が2回目で変わらないことを確認する。

期待結果: 同一rangeの再取込はreplaceとして動作し、Session・ID・duration・統計が増殖しない。`--to`はexclusiveで、最大31 local日までに分割する。

### AW-08: `titles`のprivacy境界

1. Backendを停止し、同じ専用data rootで`LIFE_TIMELINE_ACTIVITYWATCH_PRIVACY_MODE=titles`を設定して再起動する。
2. CalculatorまたはNotepadの合成window titleだけを作り、対象rangeをCLIで再生成する。
3. Timelineで非browserの合成window titleが表示されることを確認する。
4. Browserを使った場合は、browser window titleが`titles` modeで保存されないことを確認する。

期待結果: `titles`は非browserのwindow titleだけを許可し、browserのWeb詳細を複製しない。実在のtitleは記録しない。

### AW-09: `web`、query / fragment、incognito、Web watcher欠落

1. 専用browser profileで`web` modeを設定し、`http://127.0.0.1:5173/phase6-synthetic.html?fixture=phase6#detail`を開く。URLの実値は記録しない。
2. 対象rangeを再生成し、Timelineのdetailがplain textとして表示されることを確認する。
3. 保存・表示されたURLにquery、fragment、userinfoが残っていないことを確認する。URLの実値は記録しない。
4. incognito windowまたはWeb watcher停止状態で同じ確認を行う。
5. Web詳細が取得できなくてもBrowser AppSession自体は残ることを確認する。

期待結果: URLの機微な部分とincognito detailはnullまたは除去され、Web watcher欠落でもAppSessionを失わない。画面上のtitle / URLはリンクやHTMLとして実行されない。

### AW-10: privacy mode縮小後の再生成

1. `web`または`titles`で取り込んだ専用rangeを確認する。
2. Backendを同じdata rootで`app_only`へ戻す。
3. 同じrangeを`--privacy-mode app_only`付きでCLI再生成する。
4. Timeline / Dashboardをreloadし、過去のwindow title / URLが対象rangeから消えていることを確認する。

期待結果: privacy modeを狭めた対象rangeはtransactional replaceで再生成され、古いdetailが残らない。対象range外を自動で書き換える前提にはしない。

### AW-11: migrationと既存データ保持

1. Phase 5までの合成fixtureを入れた専用DBへ`uv run alembic upgrade head`を実行する。
2. Android AppSession、Photo、LocationPoint、PlaceVisitの既存画面を確認する。
3. ActivityWatch import後に同じ画面を再表示する。

期待結果: ActivityWatchのmigrationとreplaceはAndroid、Photo、LocationPoint、PlaceVisitを削除・変更しない。

## 5. 受け入れチェックリスト

| ID | 実機確認項目 | 結果 | 安全な補足 |
| --- | --- | --- | --- |
| AW-01 | `app_only`でWindows AppSessionがTimelineへ表示される | NOT TESTED | 表示あり / なし |
| AW-02 | AFK期間がPC利用時間へ含まれない | NOT TESTED | 除外確認あり / なし |
| AW-03 | AndroidとWindowsが同じTimelineへ表示される | NOT TESTED | 共通表示あり / なし |
| AW-04 | Backend停止後の起動時catch-up | NOT TESTED | 自動 / 手動 / 未復旧 |
| AW-05 | ActivityWatch停止後のretry復旧 | NOT TESTED | 既存履歴維持と復旧の有無 |
| AW-06 | manual triggerの二重click防止 | NOT TESTED | 重複なし / あり |
| AW-07 | CLI同一range再取込の冪等性 | NOT TESTED | 件数・ID・duration不変の有無 |
| AW-08 | `titles`の非browser title境界 | NOT TESTED | 境界どおり / 逸脱 |
| AW-09 | `web`、incognito、Web watcher欠落 | NOT TESTED | AppSession維持とdetail抑止の有無 |
| AW-10 | `app_only`再生成で旧detailが消える | NOT TESTED | 消去確認あり / なし |
| AW-11 | migration後の既存Fact保持 | NOT TESTED | 既存画面の回帰なし / あり |
| AW-12 | loopback-only、外部URL・tile未使用 | NOT TESTED | 条件を満たした / 逸脱 |

実施結果に書いてよい環境情報は、Windows / Android OS major version、アプリversionまたはcommit短縮SHA、実施年月、実施したシナリオIDだけです。実在のhostname、日時の詳細、URL、場所、アプリ一覧、ログは書きません。

```text
Windows OS major version: 未記録
Android OS major version: 未記録 / 対象外
App version / commit: 未記録
実施年月（任意）: 未記録
実施シナリオ: 未記録
実機操作担当: 利用者
```

## 6. 非保証と後片付け

- ActivityWatchやwatcherが停止していた期間、権限不足、Web watcher未導入、incognito、未対応browserの履歴は復元できない。
- 起動後30秒、通常15分のscheduleはdeadlineではない。sleep、負荷、通信エラー、backoffで遅延し得る。
- AFK timeoutはActivityWatch設定に依存し、life-timelineが固定値へ変更しない。
- `web` modeを選んでもURL path自体に個人情報があれば保存され得るため、専用localhost pageだけを使用する。
- 確認後は収集を停止し、Backend、Frontend、ActivityWatchを終了する。専用data rootを削除する場合は、全プロセス停止後に利用者が絶対パスと内容を確認してから手動で行う。
- DB復旧やbackup確認は、[Phase 6運用手順](phase6-operations.md)のdata root全体の手順に従う。`lifelog.db`だけ、または`thumbnails/`だけを戻さない。

## 7. 参照

- [P6-10 Issue #127](https://github.com/Megane14916/life-timeline/issues/127)
- [Phase 6 ActivityWatch詳細計画](../detailed_plan/phase6-activitywatch.md) §11〜14
- [Phase 6 ActivityWatch privacy review](phase6-activitywatch-privacy.md)
- [Phase 6運用手順](phase6-operations.md)
- [Phase 2 Tailscale Serve接続手順](phase2-tailscale.md)

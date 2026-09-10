# Phase 2受け入れ記録

## 1. 判定

Phase 2のAndroid App Usage MVPについて、Windowsの専用一時DB、Android実機、Tailscale Serve、既存のTimeline / Dashboard、required checksを対象に確認した。実機ではUsage Access、HTTPS health、手動同期、FastAPI停止からの復旧、Tailscale切断・再接続を確認し、同期済みデータの再送で重複しないことを確認した。

実tailnetのhostname、端末identity、個人のアプリ一覧、Session内容、tokenはこの記録へ保存しない。以下のURLはすべてplaceholderである。

検証日は2026年9月10日。対象はp2-08をmerge済みの`main`（commit `dedecae`）と、このIssueに対応する作業ブランチである。

## 2. 検証環境

| 項目 | 検証値 |
| --- | --- |
| PC OS | Microsoft Windows |
| Python | 3.13.15 |
| uv | 0.12.9 |
| Node.js | 24.20.0 |
| npm | 11.19.0 |
| JDK | 17 |
| Android SDK | Platform 36、Build-Tools 36.0.0 |
| Android | Tailscaleへ接続した実機 |
| Backend bind | `127.0.0.1:8000` |
| Frontend bind | `127.0.0.1:5173` |
| Backend data directory | `%TEMP%`配下のPhase 2専用絶対パス |

個人の既存`lifelog.db`は使用せず、毎回一意なPhase 2専用データディレクトリを指定する。

## 3. 第三者向け再現手順

### 3.1 Windows側

READMEのQuick Startでtoolchainを導入し、repositoryを新しいcheckoutから取得する。Backend用PowerShellで次を実行する。

```powershell
cd backend
uv sync --all-groups --frozen
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-phase2-acceptance-<unique>'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

別のPowerShellでBackendのhealthとlisten addressを確認する。

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Get-NetTCPConnection -State Listen -LocalPort 8000 |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

`status`が`ok`で、listen addressが`127.0.0.1`だけであることを確認する。`0.0.0.0`、`::`、LANアドレスが表示された場合はBackendを停止して起動引数を修正する。

Frontendは別のPowerShellで起動する。

```powershell
cd frontend
npm ci
npm run dev
```

### 3.2 Tailscale Serve

WindowsとAndroidを同じtailnetへ接続し、tailnet管理画面でHTTPS certificatesと最小ACLを設定する。Funnel、LANへの直接公開、cleartext HTTP、TLS検証の無効化は使用しない。

Backendが起動している状態でrepository rootからServeを設定する。

```powershell
.\scripts\tailscale-serve.ps1 -Action configure -LocalPort 8000
.\scripts\tailscale-serve.ps1 -Action status
.\scripts\tailscale-serve.ps1 -Action check -Endpoint 'https://<machine>.<tailnet>.ts.net'
```

表示された実URLはローカルのメモだけに控え、repository、Issue、PR、ログへ記録しない。Tailscale CLIはServeの設定・状態確認・停止をWindowsから行う場合に必要であり、Serve設定済みの通常の同期には必須ではない。Android側はTailscaleアプリが接続状態であればよい。

### 3.3 Android側

1. `./android/gradlew.bat -p android assembleDebug`でAPKをbuildし、Android Studioまたは`adb install -r`で実機へinstallする。
2. アプリを起動し、`Usage access: 要設定`が表示されたら「利用状況へのアクセス設定」を押す。
3. AndroidのUsage Access設定で`life-timeline`を許可し、アプリへ戻って`Usage access: 許可済み`を確認する。
4. `PC endpoint (HTTPS)`へServeの実URLを入力し、「PC URLを保存」を押す。URLは`https://`で始まり、query、fragment、userinfoを含めない。
5. Chromeで`https://<machine>.<tailnet>.ts.net/api/v1/health`を開き、`{"status":"ok"}`を確認する。
6. Chrome等の識別可能なアプリを開始・終了し、「収集して同期」を押す。`最終収集`、`最終同期`、`Pending`、`状態`を確認する。
7. Frontendの`http://127.0.0.1:5173/timeline?date=<date>&timezone=<timezone>`を開き、同期したAndroid AppSessionがTimelineとDashboardへ反映されたことを確認する。

## 4. 実機・障害復旧の結果

実際のhostname、tailnet名、端末identity、収集データは記録していない。

| 確認項目 | 結果 | 記録 |
| --- | --- | --- |
| Usage Access未許可から設定導線 | PASS | 未許可時は収集操作を実行できず、設定ボタンが表示される |
| Usage Access許可後の収集 | PASS | 許可後にAndroidアプリから収集を実行できた |
| Android ChromeからHTTPS health | PASS | `{"status":"ok"}`を確認 |
| Androidアプリから手動同期 | PASS | Tailscale HTTPS経由で同期を実行できた |
| FastAPI停止中のpending保持 | PASS | FastAPI停止後もPendingが維持された |
| FastAPI再起動後の再同期 | PASS | FastAPI再起動後に再同期できた |
| 再送時の冪等性 | PASS | 同じデータの再同期で重複登録が発生しなかった |
| Tailscale切断・再接続 | PASS | Tailscaleのオン／オフ切替後、再接続すると通信が復旧した |

FastAPI停止試験は、AndroidでSessionを収集してからBackendを停止し、同期失敗後にPendingが減らないことを確認した。その後、同じ専用データディレクトリでBackendを再起動して再同期し、ACK後にPendingが解消されることを確認した。

## 5. AC-01〜15

| ID | 結果 | 主な証拠 |
| --- | --- | --- |
| AC-01 | PASS | Usage Access未許可時の設定導線、許可後の実機収集、`UsageAccessChecker`のunit test |
| AC-02 | PASS | API level別mappingとSessionizerのunit test、実機での確定Session収集 |
| AC-03 | PASS | Room / source key / process再作成相当のunit・instrumentation test |
| AC-04 | PASS | FastAPI停止後のPending維持、Android再起動後のRoom保持確認 |
| AC-05 | PASS | device ID、package name、label fallback、UTC epoch ms、durationのAndroid testとSync request |
| AC-06 | PASS | 100件batch、accepted IDだけをsyncedへ更新するSyncRepository test |
| AC-07 | PASS | Backend Sync APIのatomic transaction、ACK、同一再送test |
| AC-08 | PASS | 同一payload再送時のMaster / Fact件数と`created_at_ms`を検証するBackend test、実機再送 |
| AC-09 | PASS | 422 / 409 / 500 / 503、競合・不正payload・rollbackのBackend / Android test |
| AC-10 | PASS | 同じpackageの自然キー解決を検証するBackend test |
| AC-11 | PASS | `pc-core-e2e`の実DB→Sync API→Timeline / Dashboard検証と、実機同期結果の画面確認 |
| AC-12 | PASS | Compose画面の権限、endpoint、最終収集、最終同期、Pending、error stateのinstrumentation / ViewModel test |
| AC-13 | PASS | FastAPI loopback、Tailscale Serve HTTPS、Android Chrome health、実機同期、Funnel未使用の確認 |
| AC-14 | PASS | FastAPI停止、Tailscale切断・再接続、復旧後の手動再送と重複なしの実機確認 |
| AC-15 | PASS | `frontend-ci`、`backend-ci (ubuntu)`、`backend-ci (windows)`、`android-ci`、`pc-core-e2e`、`android-instrumentation-ci`の成功とrequired設定 |

required checksはp2-08の[PR #47](https://github.com/Megane14916/life-timeline/pull/47)で成功し、mainの[保護ruleset](https://github.com/Megane14916/life-timeline/rules/22253391)で全6 checkがrequiredであることを確認した。Tailscaleの実tailnet経路はCIへ秘密情報を持ち込まず、このローカル実機記録で補完する。

## 6. Phase 3への引き継ぎ

Phase 2では、画面の「収集して同期」による単一操作と、失敗時にPendingを保持してユーザーが再試行する経路を完成させた。次の事項はPhase 3へ引き継ぐ。

- WorkManagerによる定期収集・自動同期・再起動後のunique work復旧。
- network / battery constraint、指数backoff、実行中lease、stale状態の復旧。
- UsageStatsの保持期間を超えても記録を取りこぼさない定期収集。
- OEM、multi-window、screen off、端末再起動を長期間実測した上でのcollector規約更新。
- 手動同期は診断・即時実行手段として残し、Phase 3のRepositoryからも再利用する。

Phase 3以降の実装でも、Androidで一度生成したSession ID、Roomのpending状態、version 1 Sync API、PC側の冪等性を置き換えない。

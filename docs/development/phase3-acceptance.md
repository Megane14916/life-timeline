# Phase 3受け入れ記録

## 1. 判定

Phase 3の自動収集・自動同期について、CIで確認できる範囲と、Android実機・専用PC環境で利用者が操作する範囲を分けて記録する。

この記録の初期状態は、実機操作待ちである。Doze、OEMの電池最適化、Tailscale経路、端末再起動、24時間以上の無操作運転は、エミュレータやGitHub Actionsだけでは完了扱いにしない。利用者操作が必要な項目は「5. 利用者操作が必要な項目」と「6. 受け入れシナリオ」に従って実施し、結果表へ追記する。

記録へ実際のhostname、tailnet名、端末識別子、package利用履歴、Session内容、token、raw logcat、画面キャプチャを保存しない。証跡はPASS / FAIL、経過時間、件数、状態コードなどの集計値に限定する。

## 2. 検証対象と前提

| 項目 | 記録値 |
| --- | --- |
| 対象 | P3-08「長時間利用と障害復旧を実機で受け入れる」 |
| PC OS | Windows |
| Android | Tailscaleへ接続した実機 |
| Backend bind | `127.0.0.1:8000` |
| Frontend bind | `127.0.0.1:5173` |
| PC data directory | `%TEMP%`配下の専用絶対パス |
| Android build | このPRで生成したdebug APK |
| 実機操作担当 | 利用者 |
| 記録状態 | 実機操作待ち |

### 2.1 受け入れ前の安全条件

- 個人の通常利用DBを使わず、毎回一意なPhase 3専用データディレクトリを使う。
- 実機の通常データを消去しない。可能なら検証用Androidユーザーまたは検証用端末を使う。
- Tailscale ServeはFunnel、LAN直接公開、cleartext HTTP、TLS検証無効化を使わない。
- FastAPIは`127.0.0.1`だけでlistenさせ、WindowsのLANアドレスや`0.0.0.0`へbindしない。
- 障害試験は専用DBで行い、通常運用中のpendingデータと混ぜない。
- 端末の低バッテリー試験は、利用者が安全に中断・充電できる状態で行う。意図的な過放電は行わない。

## 3. 専用環境の準備

### 3.1 Windows側

repository rootからBackend用PowerShellを開き、専用の絶対パスを作成する。`<unique>`は日時などを含む一意な値に置き換えるが、実際のパスはIssue、PR、ログへ貼り付けない。

```powershell
cd backend
uv sync --all-groups --frozen
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-phase3-acceptance-<unique>'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

別のPowerShellでhealthとlisten addressを確認する。

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
Get-NetTCPConnection -State Listen -LocalPort 8000 |
  Select-Object LocalAddress, LocalPort, OwningProcess
```

`status`が`ok`で、listen addressが`127.0.0.1`だけであることを確認する。Frontendはさらに別のPowerShellで起動する。

```powershell
cd frontend
npm ci
npm run dev
```

### 3.2 Tailscale Serve

WindowsとAndroidを同じtailnetへ接続し、対象PCのHTTPS証明書と最小ACLを確認する。実URLはローカルのメモだけに保存する。

```powershell
cd <repository-root>
.\scripts\tailscale-serve.ps1 -Action configure -LocalPort 8000
.\scripts\tailscale-serve.ps1 -Action status
.\scripts\tailscale-serve.ps1 -Action check -Endpoint 'https://<machine>.<tailnet>.ts.net'
```

Android Chromeで次を開き、`{"status":"ok"}`を確認する。

```text
https://<machine>.<tailnet>.ts.net/api/v1/health
```

確認後も、実URLをこのファイルや証跡へ書き込まない。

### 3.3 Androidアプリ

この節は利用者がAndroid実機で操作する。`adb`を使えない場合はAndroid StudioのRun / Install APKで同じ操作を行う。

```powershell
.\android\gradlew.bat -p android assembleDebug
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

1. `life-timeline`を起動する。
2. `Usage access: 要設定`なら「利用状況へのアクセス設定」を押し、Android設定で`life-timeline`を許可する。
3. アプリへ戻り、`Usage access: 許可済み`を確認する。
4. `PC endpoint (HTTPS)`へ実際のTailscale Serve URLを入力し、「PC URLを保存」を押す。URLにquery、fragment、userinfoを付けない。
5. Android ChromeでHTTPS healthを確認する。
6. アプリ画面に自動収集のschedule、最終自動収集、最終自動同期、Pending、直近の状態が表示されることを確認する。

## 4. 記録方法

### 4.1 記録してよい値

- `PASS` / `FAIL` / `BLOCKED`。
- 試験開始・復旧までの経過時間。絶対時刻が不要なら`約5分`のように記録する。
- Android画面に表示されるPending件数、WorkInfoの状態、run attempt数。
- PC側のTimeline / Dashboardの集計件数・合計時間。
- battery残量の開始値と終了値。個人情報ではない参考値として記録する。
- 失敗時の分類コード（`network`、`server`、`permission`、`unavailable`、`protocol`など）。

### 4.2 記録してはいけない値

- Tailscaleの実hostname、tailnet名、ACL、identity、IPアドレス。
- Android device ID、WorkRequest ID、Session ID、token。
- 実際のpackage名一覧、アプリ名、利用時刻、Session payload。
- raw logcat、raw `dumpsys`、SQLiteファイル、DB dump、画面キャプチャ。
- パスワード、access token、証明書、秘密鍵。

### 4.3 WorkManagerの確認

実機上での確認時だけ、利用者が端末上で確認する。出力を保存せず、次のような集計結果だけを表へ転記する。

```powershell
adb shell dumpsys jobscheduler | Select-String 'life_timeline_usage_collection_v1|life_timeline_app_session_sync_v1'
```

期待値は、collectionがunique periodic workとして最大1件、syncがunique workとして最大1件である。画面の自動schedule表示と、WorkInfoの状態が矛盾しないことも確認する。

## 5. 利用者操作が必要な項目

この表の「操作」は、このcheckoutから自動実行できず、利用者がAndroid実機または専用PCで行う必要がある。実施後に結果・経過時間・集計値だけを「7. 結果」へ記録する。

| 操作 | 利用者が行うこと | 完了の判定 |
| --- | --- | --- |
| Usage Access | Android設定で許可を取り消し、再度許可する | 取消時はcursorが進まず、再許可後に収集が再開する |
| 24時間運転 | アプリ画面を閉じ、通常のアプリ利用を続け、24時間以上待つ | 手動ボタンなしでRoomへ収集され、PCへ蓄積する |
| PC停止 | Backendを`Ctrl+C`で停止し、端末で新しいSessionが発生する状態にする | Pendingが減らず、error分類がnetwork / server系になる |
| PC復旧 | 同じ専用DBでBackendとServeを復旧する | 手動ボタンなしでpendingが古い順に減る |
| Tailscale切断 | Android Tailscaleを切断する | pendingを保持し、再接続後に自動復旧する |
| Network切替 | Wi-Fiとmobile networkを安全な範囲で切り替える | 接続回復後にretryが復旧する |
| Android再起動 | 端末を再起動し、アプリを開かずに待つ | periodic workとretry中workが復旧する |
| Process kill | `adb shell am kill com.megane14916.lifetimeline`を実行する | stale lease回収後、未ACKが再送される |
| 近接実行 | WorkInfoがRUNNINGまたはretry中に「収集して同期」を一度押す | 二重送信、cursor後退、不正ACK表示がない |
| Battery Saver | Battery Saverを有効にし、可能なら充電器を外す | collectionは継続し、syncは制約解除後に実行される |
| 再送確認 | 同一期間の収集・送信を再度実行する | Session ID、PC件数、Timeline / Dashboard集計が増殖しない |

`adb shell am force-stop`はOSのforce-stop制約を含む別試験であり、無操作復旧の成功条件には使わない。Phase 3は、通常のprocess kill後のstale lease回収と、次回アプリ起動時のschedule再確認を対象とする。

## 6. 受け入れシナリオ

次の順序で実施する。障害試験の前後でPending件数、WorkInfo状態、PC側の集計件数だけを記録する。

### 6.1 ベースライン

1. 専用PC DB、Backend、Frontend、Tailscale Serve、Android endpointを準備する。
2. Usage Accessが許可済みで、画面に自動scheduleが有効と表示されることを確認する。
3. Androidで少数の検証用アプリ操作を行い、画面を閉じる。
4. 15分周期と5分flexの範囲で自動収集が実行され、Pendingが記録されることを確認する。
5. PC起動中は、手動ボタンを押さずにsyncが実行され、Pendingが0へ近づくことを確認する。

### 6.2 Backend停止と復旧

1. Backendを停止する。
2. Androidの画面を開かず、検証用アプリを操作して新しいSessionを作る。
3. Pendingが維持され、syncがretryまたはnetwork / server系の状態になることを確認する。
4. Backendを同じ専用DBで再起動する。
5. ServeとTailscaleが接続状態に戻った後、手動ボタンを押さずにPendingが減ることを確認する。
6. PC Timeline / Dashboardの集計が一度だけ増え、重複がないことを確認する。

### 6.3 Tailscale・network切替

1. Tailscaleを切断するか、Wi-Fiとmobile networkを切り替える。
2. Pendingが消えず、未ACK SessionがRoomに保持されることを確認する。
3. Tailscaleを再接続し、Android Chromeのhealthが復旧することを確認する。
4. アプリ画面を開かず、自動retry後にPendingが減ることを確認する。

### 6.4 再起動・process kill・手動競合

1. retry中またはPendingがある状態でAndroidを再起動する。
2. アプリを開かずに、periodic workとsync workが再作成されることを確認する。
3. 送信中の状態を作り、`adb shell am kill`でprocessだけを終了する。
4. leaseが期限切れになった後、次回workerがstale leaseを回収し、未ACKだけを再送することを確認する。
5. WorkInfoがRUNNINGのタイミングで手動の「収集して同期」を一度だけ押す。
6. 二重登録、ACK済み件数の不整合、cursor後退がないことを確認する。

### 6.5 Usage Access・Battery Saver・長時間運転

1. Usage Accessを取り消し、画面に設定要確認が表示されることを確認する。
2. 取消中にcursorと既存Pendingが変化しないことを確認する。
3. Usage Accessを再許可し、新しい収集が再開することを確認する。
4. Battery Saverを有効にし、collectionが不必要に停止せず、syncだけが遅延することを確認する。
5. アプリ画面を閉じたまま24時間以上通常利用する。
6. 収集間隔、欠落の有無、Pending推移、復旧時間、battery残量の参考値を記録する。

## 7. 結果

実機操作前は、結果を`PENDING（利用者操作待ち）`とする。PASSへ変更する場合は、raw dataではなく集計値と確認方法を記録する。

| ID | 結果 | 主な証拠 / 利用者記入欄 |
| --- | --- | --- |
| AC-01 | PENDING | Phase 2受け入れの回帰、6 required checks、実機smoke |
| AC-02 | PASS候補 | scheduler / WorkManager test、実機WorkInfoのunique件数 |
| AC-03 | PENDING | 24時間記録、手動操作なしのRoom収集 |
| AC-04 | PENDING | offline / low battery時のcollectionと実機記録 |
| AC-05 | PENDING | constraint test、network / battery切替の実機記録 |
| AC-06 | PASS候補 | Application起動、収集後、URL保存後のunique sync test |
| AC-07 | PASS候補 | retry classifier、run attempt、backoffのCI結果 |
| AC-08 | PENDING | PC停止・sleep・FastAPI停止・Tailscale切断中のPending件数 |
| AC-09 | PENDING | 復旧までの経過時間、手動操作なしのPending減少 |
| AC-10 | PENDING | 部分成功後のPending件数と古い順の再開 |
| AC-11 | PENDING | 再送前後のPC件数、Timeline / Dashboard集計 |
| AC-12 | PENDING | Android再起動後のWorkInfoとretry復旧 |
| AC-13 | PENDING | workerと手動操作の近接実行結果 |
| AC-14 | PENDING | process kill後のstale lease回収と未ACK再送 |
| AC-15 | PENDING | Usage Access取消・再許可時のcursor / Pending |
| AC-16 | PENDING | 実機画面のschedule、最終実行、Pending、error分類 |
| AC-17 | PENDING | 24時間以上の通常利用とPC蓄積件数 |

### 7.1 記入用の最小記録

```text
実施日:
対象commit:
実機操作担当:
連続運転時間:
開始Pending:
最大Pending:
終了Pending:
PC側の開始件数:
PC側の終了件数:
復旧時間の代表値:
battery開始 / 終了（参考値）:
結果:
失敗時の対応Issue:
```

## 8. 失敗時の切り分け

| 症状 | 確認順 | 期待する対応 |
| --- | --- | --- |
| Pendingが増える | Android endpoint、Tailscale、Serve、FastAPI health | 経路を復旧し、network / serverのretryを待つ |
| Pendingが減らない | WorkInfo、Battery Saver、Tailscale接続、Backendログの分類コード | PC・network制約を復旧して自動再送を確認する |
| cursorが進まない | Usage Access、`permission` / `unavailable`状態 | 権限を再許可し、未許可中のSessionをデータ欠落と扱わない |
| scheduleがない | アプリを一度起動し、画面のschedule表示とWorkInfoを確認 | force-stop中ではないことを確認し、起動後のensureScheduledを再確認する |
| PC件数が増殖する | 同一ID再送か、PC専用DBを使っているかを確認 | raw payloadを保存せず、件数と集計だけを比較する |
| 復旧しない | deviceの電源、Tailscale、Serve、FastAPI、constraintを順に確認 | 再現手順と対応Issueをこの記録へ追記する |

## 9. 完了条件

- AC-01〜17の結果がすべてPASSまたは理由付きのBLOCKEDになっている。
- 24時間以上の通常利用で、画面操作なしにPCへデータが蓄積されている。
- PC停止、Tailscale切断、network切替、Android再起動、process kill、Usage Access取消、Battery Saverの復旧結果が記録されている。
- 再送前後でPC件数、Timeline / Dashboard集計に重複がない。
- 失敗した項目には再現手順と対応Issueがある。
- 記録にprivate hostname、端末識別子、実アプリ利用データ、secretが含まれていない。
- 6つのrequired checksが成功している。

## 10. 参照

- [Phase 3詳細計画](../detailed_plan/phase3-automatic-sync.md)
- [Phase 2受け入れ記録](phase2-acceptance.md)
- [Tailscale Serve接続手順](phase2-tailscale.md)
- [技術設計](../technical-design.md)

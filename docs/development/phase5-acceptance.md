# Phase 5 実機受け入れ手順・記録

## 1. 判定

P5-10はAndroid実機での通常系受け入れであり、GitHub Actionsやこのcheckoutだけでは実機上のbackground配信を確認できない。この文書では、安全な専用環境と確認手順を用意し、利用者が実施した結果だけを記録する。

| 確認対象 | 状態 | 備考 |
| --- | --- | --- |
| 自動テスト・required CI | 実装側で確認 | 合成データによる契約とCIの結果。実機のOS配信を保証しない |
| Android実機の通常系 | 未実施（利用者の確認待ち） | 本文の「5. 通常系の実機手順」を実施後に結果を記録する |
| 拡張実機シナリオ | 任意・未実施 | Doze、OEM差、reboot等。通常系とは分けて扱う |

実機操作をこのcheckoutで行っていないため、PASSとは記録しない。P5-10の完了は、画面OFFを含む収集、PC復旧後の同期、その日のrouteとPlaceVisitを利用者が確認してから判断する。

## 2. 対象と自動テストとの境界

- 対象: P5-10「background収集の通常系を実機で受け入れる」（Issue #103）。
- 実機で確認する範囲: permission導線、screen off中のbackground location、PC停止中の端末内pending保持、PC復旧後の同期、Map / Timeline上のrouteとPlaceVisit。
- Unit / instrumentation / Backend / Frontend / CIで確認する範囲: 合成fixtureによるpermission state、Room / WorkManager、sync API、冪等性、visit生成、Timeline / Map表示、tile privacy。
- OSの配信時刻はbest-effortであり、screen off中の5分ごとの到着、完全に途切れないroute、一定の精度・battery消費は合格条件にしない。
- Map / Timelineに実データが見えることを確認するが、個々の地点・訪問先の内容はこの文書やIssue / PRに記録しない。

## 3. 個人データとネットワークの安全条件

位置情報は自宅・職場や日常の行動を特定できる機微情報である。テストで実際の位置を収集する前に、以下を確認する。

- 位置収集は明示的opt-inである。普段使いの端末で試す場合、テスト中の位置履歴が端末内に残ることを理解してから有効にする。可能なら検証用端末またはAndroidの別ユーザーを使う。
- アプリのデータ消去やアンインストールでpending位置を消さない。アプリを無効化しても過去のpendingとPCの履歴は消えないため、テスト後に記録を残したくない場合は最初から検証用環境を使う。
- PC側は個人の通常DBではなく、毎回新しい専用データディレクトリを使う。PCプロセス再起動時も同じディレクトリを指定し、空の別DBを誤って起動しない。
- FastAPIは`127.0.0.1`だけでlistenさせ、Tailscale ServeのHTTPS経由でAndroidから接続する。Funnel、LANへの直接公開、cleartext HTTP、TLS検証無効化は使わない。実URLは端末内のアプリ設定だけに置き、repository、Issue、PRへ書かない。
- Mapのonline basemap / tile表示は初期状態のままOFFにする。実位置を外部tile providerへ送る設定にしない。
- 実座標、地名、住所、経路やMapの画像、個々のpoint時刻、端末ID、hostname / tailnet、token、DBファイル、raw logcat / backend log、packet captureを保存・共有しない。記録はPASS / FAIL / BLOCKED、OS / app build、項目を実施したかなどの集計情報だけにする。

### 3.1 PCの専用試験環境

Phase 3と同じ隔離方法を使う。詳細な依存関係導入、health確認、Frontend起動、Tailscale Serve設定は[Phase 3受け入れ記録 §3](phase3-acceptance.md#3-専用環境の準備)を参照する。ここでは特に、データパスをP5専用の新しい名前にして、通常利用DBを指定しないこと。

Backend用PowerShellで、repository rootから実行する。`<unique>`は一意な文字列に置き換え、実際に作ったパスはIssue / PRやこの記録へ貼り付けない。

```powershell
cd backend
uv sync --all-groups --frozen
$env:LIFE_TIMELINE_DATA_DIR = Join-Path $env:TEMP 'life-timeline-phase5-acceptance-<unique>'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000
```

別のPowerShellでFrontendを起動し、Tailscale ServeがこのBackendのloopback port `8000`へHTTPS reverse proxyしていることを確認する。Backendを停止・再起動する場合は、最初に設定したのと同じ専用データディレクトリを使う。

### 3.2 Android buildと事前確認

repository rootでdebug APKをbuildし、検証用端末へinstallする。`adb`が利用できない場合はAndroid Studioから同じdebug APKをinstallする。

```powershell
.\android\gradlew.bat -p android assembleDebug
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

1. AndroidとPCが同じtailnetで接続されていることを確認する。
2. Android Chromeで専用PCのHTTPS `/api/v1/health`を開き、`{"status":"ok"}`を確認する。URLそのものは記録しない。
3. `life-timeline`を開き、PC endpoint欄へ同じHTTPS URLを入力し、「PC URLを保存」を押す。
4. 画面下部の「位置情報の収集」で「位置情報収集を有効にする」を押す。
5. 位置権限は段階的に許可する。foregroundでは「正確な位置情報」を選び、その後「バックグラウンド位置情報を設定」からAndroid設定を開いてbackground利用を許可する。Androidのversionにより設定名が異なるため、screen off中も許可される選択肢を選ぶ。
6. Androidの端末設定でも位置情報サービスがONであることを確認する。アプリに戻り、位置情報状態がpermission不足やサービスOFFではないこと、登録状態が成功相当であることを確認する。
7. 位置収集を有効にする前から存在する履歴を変更・消去しない。テスト用PC DBが空の専用DBであることを確認する。


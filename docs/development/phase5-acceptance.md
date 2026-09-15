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

Phase 3と同じ隔離方法を使う。詳細な依存関係導入、health確認、Frontend起動、Tailscale Serve設定は[Phase 3受け入れ記録](phase3-acceptance.md)を参照する。ここでは特に、データパスをP5専用の新しい名前にして、通常利用DBを指定しないこと。

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
6. Androidの端末設定でも位置情報サービスがONであることを確認する。アプリに戻り、位置情報状態がpermission不足やサービスOFFではないこと、登録状態が成功相当であることを確認する。登録に失敗した場合は、アプリの「位置登録の直近エラー」に表示される段階名・例外型・Play services status codeだけを共有する。例外メッセージやlogcatには位置・端末等の情報が含まれる可能性があるため共有しない。
7. 位置収集を有効にする前から存在する履歴を変更・消去しない。テスト用PC DBが空の専用DBであることを確認する。

## 4. 通常系の実機手順

テストは位置履歴をPCの専用DBへ同期するため、実際の位置がMapに表示される。自宅・職場などを含むルートを避けるか、検証用端末・Androidユーザーを使い、位置収集を有効にすること自体に同意できる範囲で実施する。

1. PCのBackend、Frontend、Tailscale Serveが起動し、専用DBのhealthが正常であることを確認する。Mapの背景地図を有効にする操作はしない。
2. Androidアプリで、位置収集が有効、正確なforeground位置権限とbackground位置権限が許可済み、PC endpointが設定済みであることを確認する。必要なら「登録状態を確認」を押す。
3. 画面OFFを含む移動と、動かずに15分以上過ごす時間を同じテスト日に含める。滞在の前後に無理のない移動を挟む。一定周期の点取得やrouteの完全性は要求しない。
4. PCを停止する。簡易な停止試験では、Backendのuvicornを実行しているPowerShellで`Ctrl+C`を押す。PC自体をシャットダウンする場合も、同じ専用データディレクトリを使って後で起動する。Frontend / Tailscale Serveが残っていてもBackend停止中は同期先へ届かない。
5. PC停止中にscreen offを含む移動と15分以上の滞在を行う。端末の電池を安全に保ち、不要に長時間位置収集を続けない。
6. 端末を起こしてアプリを開き、「位置情報の最終受信」と「位置情報pending」を確認する。値や時刻を撮影・転記せず、「screen off中の受信を確認できたか」「pendingが保持されたか」だけを記録する。Androidのbackground配信は遅延・batch化し得るため、決まった分数内に届くことを合格条件にしない。
7. Backendを同じ専用データディレクトリで再起動し、healthとTailscale経路を確認する。Androidではまず自動同期の状態を観察する。一定時間後もpendingが残る場合に限り、アプリを開いてから「未同期の位置情報を送信」を一度押してよい。自動復旧か手動操作後の復旧かを区別して記録する。
8. pendingが解消したら、PCのFrontendでテスト日と端末のtimezoneに対応する日次TimelineとMapを確認する。Mapではroute / visit layerを表示し、背景tileはOFFのままにする。screen off中の複数pointによるrouteと、15分以上の滞在に対応するPlaceVisitが少なくとも1つ表示されることを確認する。
9. 同じ日付のTimelineにPlaceVisitが表示されること、通常の再表示・reloadでpoint / visit / timeline itemが増殖して見えないことを確認する。実機UIにはACK済みbatchを強制再送する機能がないため、既に同期済みの座標をDB操作で再送しない。厳密な同一payload再送の冪等性は合成データによる自動テストで確認する。
10. 確認後、アプリの「位置情報収集を無効にする」を押す。無効化しても端末内pendingやPC履歴は削除されない。pendingが残った状態でテストを終える場合はそのまま保持し、アプリデータ消去で解決しない。

テストをlocal midnightを跨がずに行うと、日付境界の切り分けが簡単になる。跨いだ場合はroute / visitが記録時刻とtimezoneに対応する日へ表示されることを確認するが、実際の日付や時刻の列はここへ記録しない。

### 4.1 現在地を1回取得する実機確認

「現在地を1回取得して送信」は、押したときだけforegroundで新しいfixを1点要求し、通常の保存・同期経路へ渡す操作である。現在地という機微情報をPCへ記録するため、テスト位置とPCの専用DBを確認し、記録してよい場所でのみ押す。OSが30秒以内に新しいfixを得られなければ保存されない。

1. 位置収集が有効で、位置情報サービスと権限が許可されていることを確認する。
2. Androidで「現在地を1回取得して送信」を押す。成功表示だけで判断せず、「位置情報の最終受信」とpendingが更新されたか確認する。
3. PC endpointが設定されている場合は同期の試行・成功とpending解消を確認し、PCのMapを端末のtimezoneの日付で開く。
4. 同じ場所にいる間に取得した複数fixがPlaceVisitになる場合、Mapに滞在地点が表示され、滞在中の点から不自然なroute線が伸びないことを確認する。単独の移動fixは線ではなくpointとして表示される。
5. fixを得られない場合は、表示された安全な案内に従って位置サービスを確認して再試行する。座標、raw log、実際の住所は記録しない。

### 4.2 今回の確認で判明した既知の制約

同じ場所に留まった状態で「現在地を1回取得して送信」を確認した際、地図上で滞在地点とは別の位置記録が複数表示され、不自然な方向へ点がまとまる事象を確認した。Backendを新しい専用データディレクトリで起動した場合は再現せず、既存のデータディレクトリで再表示した場合に確認された。

この事象について、緯度・経度の入れ替えやAndroidからBackendまでの座標変換ミスは確認されなかった。Fused Locationが返した範囲内の座標を、現在の実装がraw `LocationPoint`として保持し、精度が`1,000m`以下の点をMapのroute候補にするため、GPS由来の不正確なfixが表示される可能性がある。背景の位置更新はbatchで複数点が届くため、明示的なone-shot取得1回だけでなく、同日に受信済みの点もMapの集計対象になる。

Phase 5ではSource of Truthであるraw位置を、移動距離・速度・mock判定などの推測的なheuristicで自動削除・補正しない。今回もそのような複雑な異常点フィルターは追加しない。滞在地点で説明できる点をroute線から外す修正は行うが、滞在地点から離れたfixを正しい・誤りと自動判定する機能は提供しない。将来対応する場合は、raw保全、表示用のsuspect判定、false positive時の安全性を別Issueで設計する。

実機受け入れでは、Mapに点やrouteが表示されたことと、全点が実際の移動を正確に表すことを分けて確認する。異常な点を見つけた場合は座標、住所、時刻、スクリーンショット、raw logをIssue / PRへ添付せず、症状カテゴリだけを記録する。

### 4.3 結果の読み方

- `PASS`: その項目を画面上で確認した。
- `FAIL`: 前提を満たして実施したが、期待する状態にならなかった。個人位置情報を含まない症状カテゴリだけを記録する。
- `BLOCKED`: permission、端末設定、ネットワークなどの前提を満たせず実施できなかった。
- `NOT TESTED`: まだ試していない。未実施をPASSへ変えない。
- 画面OFF後にpointの到着が遅れた場合、配信間隔が固定でないことだけからFAILと断定しない。一方、確認を終えても収集が確認できなければ未達として記録し、追加で常時追跡を続けない。
- PlaceVisitが表示されない場合も、訪問先の詳細や座標を記録しない。permission精度、pending同期完了、Map / Timelineの日付とtimezone、tile OFFを確認し、再現を安全に説明できる場合は別Issueで追跡する。

## 5. 任意の拡張実機シナリオ

通常系受け入れの完了条件には含めない。試す場合も専用端末・専用PC DBを使い、意図的な過放電、個人DBの破壊、長時間の無監視収集はしない。

- approximate locationでの表示品質とPlaceVisit非生成の挙動。
- permission取消 / auto-reset、位置サービスOFFからの回復。
- 端末再起動、app update、process kill、force-stop後のregistration。
- Doze、OEM battery optimization、24時間 / 7日運転。
- Wi-Fi / mobile / Tailscale切替、PC長時間停止、200件超backlog。
- 地下、屋内、高層階、高速移動、timezone / DST / 日付境界。
- battery、pending解消時間、accuracy分布などのprivacy-safeなaggregate計測。

拡張項目は実施したものだけを記録し、未実施は`NOT TESTED`とする。座標を含むスクリーンショット・ログ、端末識別子、hostname、DBやbackupは添付しない。

## 6. 実施結果

この表は実機操作後に利用者が更新する。現在のcheckoutでは実施していないため、結果欄はすべて未実施のままにしている。

| 項目 | 結果 | 記録してよい補足 |
| --- | --- | --- |
| 正確なforeground + background権限と位置収集opt-in | NOT TESTED | permission導線が完了したかだけ |
| screen off中に複数pointを端末で受信 | NOT TESTED | はい / いいえ。時刻・座標は不可 |
| PC停止中の端末pending保持 | NOT TESTED | 保持されたかだけ |
| PC復旧後の同期とpending解消 | NOT TESTED | 自動 / 手動 / 未復旧の区別 |
| Mapの日次route表示 | NOT TESTED | 表示あり / なし |
| PlaceVisitが1件以上表示 | NOT TESTED | 表示あり / なし |
| Timelineに同じPlaceVisitが表示 | NOT TESTED | 表示あり / なし |
| 通常の再表示後に明らかな重複がない | NOT TESTED | はい / いいえ。画面画像は不可 |
| online basemap / tileがOFF | NOT TESTED | OFF確認のみ |
| 実機での厳密な同一batch再送 | NOT TESTED | UIから実行しない。冪等性はCIで確認 |

実施時に追記してよい環境情報は、Android OS major version、アプリversionまたはcommit短縮SHA、screen off / PC停止 / 移動 / 15分滞在を行ったかだけとする。実施日を記録する場合は年月までにし、位置履歴の時刻を残さない。

```text
Android OS major version: 未記録
App version / commit: 未記録
screen off: NOT TESTED
PC停止: NOT TESTED
移動: NOT TESTED
15分以上の滞在: NOT TESTED
実施年月（任意）: 未記録
実機操作担当: 利用者
```

### 6.1 後片付けと失敗時

- テスト終了後、位置収集をOFFにし、Backend / Frontendを停止する。Backendの専用DBを再起動する場合はデータディレクトリを取り違えない。
- 専用DBを消去する必要がある場合、本手順は自動削除を行わない。関連プロセス停止後に、利用者が実際の専用絶対パスと中身を確認し、他のデータが含まれないと確かめた場合だけ、手動で後片付けする。
- 失敗時は、permission未完了、OS配信なし、pending保持不可、同期不可、Map / Timeline表示なし、visitなし、tile設定など、症状カテゴリだけを記録する。正確な場所・時刻やraw logがなければ原因を絞れない場合でも、それらを共有せず、privacy-safeな再現条件へ置き換える。
- backend / Androidのログは位置情報や識別情報を含む可能性がある。Issue / PRへ貼らず、必要な場合は値を一切含めずに再現できる形を開発者と相談する。

## 7. 参照

- [P5-10 Issue #103](https://github.com/Megane14916/life-timeline/issues/103)
- [Phase 5詳細計画 §12 / P5-10](../detailed_plan/phase5-location.md)
- [Phase 5位置情報の運用手順](phase5-location-operations.md)
- [Phase 3受け入れ記録](phase3-acceptance.md)
- [Phase 2 Tailscale Serve手順](phase2-tailscale.md)

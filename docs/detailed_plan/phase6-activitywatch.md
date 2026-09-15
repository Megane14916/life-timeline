# Phase 6 詳細実装計画: ActivityWatch

- 作成日: 2026-09-15
- 対象: Windows PC上のActivityWatch履歴をローカルで取り込み、AppSession・PC固有詳細・利用時間としてTimeline / Dashboardへ統合する
- 前提: Phase 5のLocationPoint保存、PlaceVisit生成、Timeline / Map、privacy-safeな診断、既存6 required checksがmainへ反映済みであること

## 1. 参照資料とPhase 6の位置付け

| 資料                                                        | 確定済みの前提                                                                              | Phase 6での扱い                                                                    |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| [implementation-plan.md](../implementation-plan.md)         | ActivityWatch REST Adapter、PC利用時間、Web履歴、Timeline統合を対象とする                   | Windows PCの実データを既存の共通AppSession経路へ投入する                           |
| [product-spec.md](../product-spec.md)                       | Desktop Activityはアプリ、active window、Webページを扱う                                    | AFKを除いたPC利用を保存し、詳細は明示的なprivacy modeに従う                        |
| [data-model.md](../data-model.md)                           | PC利用も`app_sessions`へ保存し、固有情報だけ`desktop_session_details`へ分離する             | `desktop_sessions`という別Fact tableは作らず、既存モデルを拡張する                 |
| [architecture.md](../architecture.md)                       | ActivityWatchをPC側collectorとしてREST経由で利用する                                        | ActivityWatchの内部DBや設定fileを直接読まず、loopback REST Adapterで隔離する       |
| [technical-design.md](../technical-design.md)               | UTC、冪等ID、正規化Fact、再生成可能な派生データを使う                                       | UTC日chunk単位の置換とdeterministic IDで再取込可能にする                           |
| [phase5-location.md](phase5-location.md)                    | Timeline union、timezone query、raw / derived分離、batch、lease、safe diagnosticsが完成済み | PC collectorへ同じ失敗安全性を適用するが、Android componentは共有しない            |
| [phase5-acceptance.md](../development/phase5-acceptance.md) | 自動検証、通常系実機、OS依存の任意確認を分離する                                            | ActivityWatch fixtureによる自動検証と、個人情報を残さないWindows実機確認を分離する |
| [toolchains.md](../development/toolchains.md)               | BackendはPython 3.13、FastAPI、SQLAlchemy、SQLiteを使用する                                 | HTTP clientをruntime dependencyへ固定し、Python 3.13との互換性をCIで確認する       |

Phase 5完了時点で、AndroidのApp Usage、写真、位置情報はPCへ自動同期され、同じTimeline上で表示できる。WindowsのダミーAppSessionも既存fixtureに存在するため、Phase 6の中心は新しい表示基盤の作成ではなく、ActivityWatchのsource固有イベントを既存の`devices`、`apps`、`app_sessions`へ安全に正規化することである。

ActivityWatchのraw eventはActivityWatch側をSource of Truthとする。life-timelineに保存した`source = "activitywatch"`のAppSessionとDesktopSessionDetailはローカルで長期閲覧できるcopyであると同時に、指定期間をActivityWatchから決定的に再生成できるmaterialized Factとして扱う。通常取込ではActivityWatchのbucketを変更・削除せず、read-only APIだけを使用する。

### 1.1 ActivityWatch公式仕様から採用する制約

- ActivityWatchはwatcherごと・hostごとにbucketを持ち、bucket metadataには`type`、`client`、`hostname`がある。bucket IDの文字列patternだけに依存せずmetadataを検証する。[Buckets and events](https://docs.activitywatch.net/en/latest/buckets-and-events.html)
- eventはUTCのISO 8601 `timestamp`、秒単位の`duration`、event type固有の`data`を持つ。life-timelineでは整数millisecondへ変換し、浮動小数点誤差を境界testで固定する。
- Windowsの標準watcherでは`currentwindow`が`app`と`title`、`afkstatus`が`afk` / `not-afk`を提供する。Web extensionの`web.tab.current`は`url`、`title`、`audible`、`incognito`を提供し得る。
- PC利用時間はwindow eventそのものの総和ではなく、`not-afk` periodとのintersectionとして扱う。これはActivityWatch公式のcanonical queryと同じ基本方針である。[Working with ActivityWatch Data](https://docs.activitywatch.net/en/latest/examples/working-with-data.html) / [aw-webui canonical query](https://github.com/ActivityWatch/aw-webui/blob/master/src/queries.ts)
- Web eventは対応するbrowser windowがactiveな期間だけ採用する。browser watcher単体のeventをPC全体のactive sessionとして数えない。
- ActivityWatchの標準serverはloopback利用を前提とし、REST APIは変更の可能性があると公式資料に明記されている。実装開始時に使用中のstable releaseと`http://127.0.0.1:5600/api/`のinteractive specificationを再確認し、取得shapeをcontract fixtureで固定する。[REST API](https://docs.activitywatch.net/en/latest/api/rest.html)
- `aw-watcher-afk`の既定timeoutは180秒だがユーザー設定で変更可能である。life-timeline側で固定180秒を再適用せず、ActivityWatchが生成した`not-afk` eventを正とする。[Configuration](https://docs.activitywatch.net/en/latest/configuration.html)
- window title、URL、document名、chat subjectは機微情報を含み得る。payload、bucket ID、hostname、title、URLをlog、test artifact、受け入れ記録へ出さない。

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
ActivityWatch on Windows
  ├─ currentwindow bucket
  ├─ afkstatus bucket
  └─ web.tab.current bucket（任意）
       ↓ loopback read-only REST
ActivityWatchAdapter
  ├─ bucket metadataを検証して同一hostのsourceを選択
  ├─ UTC日chunkでeventをbounded取得
  ├─ not-afkとのintersectionだけをactive timeにする
  ├─ active browser windowへWeb detailを重ねる
  ├─ privacy modeに従ってtitle / URLを削減
  └─ deterministic AppSessionへ正規化
       ↓ 1 UTC日単位のtransactional replace
SQLite
  ├─ devices / apps
  ├─ app_sessions (source = activitywatch)
  ├─ desktop_session_details
  └─ activitywatch_import_states
       ↓
Timeline / Dashboard
  ├─ AndroidとWindowsのSessionを同じ時系列で表示
  ├─ PC app別・platform別利用時間を既存統計から集計
  └─ 許可された場合だけwindow / Web detailを表示
```

ActivityWatchまたはlife-timeline Backendが一時停止しても、次回起動時にcursor以降をcatch upできる。取得、schema validation、正規化のいずれかが失敗したUTC日については、既存Sessionを削除せずcursorも進めない。再実行では同じsource eventから同じIDと内容を生成し、重複を作らない。

### 2.2 実装するもの

- ActivityWatch連携の明示opt-inと、`app_only` / `titles` / `web`の3段階privacy mode。
- `127.0.0.1:5600`だけへ接続するread-only REST Adapter。
- `/api/0/info`、`/api/0/buckets/`、`/api/0/buckets/{bucket_id}/events`の最小shape validation。
- metadataを使ったWindows host、`currentwindow`、`afkstatus`、任意`web.tab.current` bucketのdiscovery。
- UTC日chunkのbounded fetch、件数上限時の再分割、初回lookback、rolling refresh、cursor。
- window / not-afk / Web periodの決定的なintersectionとAppSession生成。
- Windows app identifierの正規化、deterministic Device / App / Session ID。
- Alembic `0004_activitywatch`による`desktop_session_details`と`activitywatch_import_states`。
- 日chunkごとのtransactional replace、DB lease、retry分類、safe diagnostics。
- Backend lifespan内のunique collector schedule、起動時catch-up、手動診断trigger、再生成CLI。
- Timeline APIのAppSession detail拡張と既存StatisticsへのWindows実データ統合。
- React TimelineのPC詳細表示、platform別利用時間、ActivityWatch状態と「今すぐ取り込む」診断UI。
- pure unit、Backend integration、migration、Frontend、実SQLite E2E、Windows実機受け入れ。
- README、上位設計、data-model、toolchains、Phase 6受け入れ記録の更新。

### 2.3 Phase 6に含めないもの

| 対象                                                                 | 実施時期・理由                                                                                                     |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| ActivityWatch本体、watcher、browser extensionの自動install / update  | 外部製品の導入と権限付与はユーザーが公式手順で行う                                                                 |
| ActivityWatch bucketへの書込み・編集・削除                           | life-timelineはread-only consumerとし、原本を変更しない                                                            |
| ActivityWatch内部SQLiteや設定fileの直接読取り                        | 実装差・version差へ密結合するためREST境界だけを使う                                                                |
| LAN / Tailscale上のActivityWatch server                              | REST serverはloopback固定とし、remote URLや認証機構を追加しない                                                    |
| Linux / macOS collectorの実機保証                                    | modelは拡張可能にするが、Phase 6の受け入れ環境はWindows                                                            |
| editor watcherのfile path、project path、terminal command、clipboard | 情報量とprivacy規約を別途決定する必要がある                                                                        |
| audible browser tabをAFK中の利用として加算                           | 初版はinput activity由来の`not-afk`だけを利用時間とする                                                            |
| Category自動分類、productive / distracting判定                       | Phase 7。ActivityWatch側categoryをそのままMasterへ複製しない                                                       |
| 任意文字列検索、URL検索、domain別Statistics                          | Phase 7。Phase 6はTimeline上の表示とapp別集計まで                                                                  |
| detail編集、redaction UI、期間削除                                   | Phase 7。Phase 6は取込前のprivacy modeと全期間再生成手順を提供する                                                 |
| Windows service、system tray、自動ログオン起動                       | Phase 8 Desktop Packaging。Phase 6ではBackend稼働中のscheduleと起動時catch-upを使う                                |
| ActivityWatchデータのbackup / export                                 | ActivityWatch側原本のbackupはActivityWatchの責務。life-timelineの既存data root backupには取込済みSessionが含まれる |
| 複数hostを一つのWindows deviceへ自動merge                            | hostname変更やPC移行を誤結合しない。必要なら別deviceとして扱う                                                     |

## 3. 確定する振る舞い

### 3.1 opt-in、設定、到達性

ActivityWatchがinstall済みでも自動取込を開始しない。`LIFE_TIMELINE_ACTIVITYWATCH_ENABLED=true`を明示したBackendだけがscheduleを登録する。既定値は`false`である。

| 設定 / 状態              | 動作                                                                                                   |
| ------------------------ | ------------------------------------------------------------------------------------------------------ |
| enabled未指定 / false    | ActivityWatchへrequestせず、既存の取込済みSessionは表示を続ける                                        |
| enabled true、server停止 | Backend healthと既存APIは正常を維持し、statusは`unavailable`、既存Sessionとcursorは維持する            |
| window bucketなし        | statusを`missing_window_bucket`とし、書込みもcursor更新もしない                                        |
| AFK bucketなし           | active timeを推測せず`missing_afk_bucket`とし、書込みもcursor更新もしない                              |
| Web bucketなし           | AppSessionは取り込み、statusに`web_details_unavailable`を付ける。`web` modeでもtitle / URLはnullになる |
| schema非互換             | statusを`incompatible_api`とし、対象chunkをreplaceせず次回へ残す                                       |
| privacy mode変更         | rolling refreshだけで過去detailを混在させず、CLIによる対象期間の再生成を案内する                       |

設定値は`Settings`へ集約し、API requestごとに環境変数を読み直さない。

```text
LIFE_TIMELINE_ACTIVITYWATCH_ENABLED=false
LIFE_TIMELINE_ACTIVITYWATCH_DETAIL_MODE=app_only
LIFE_TIMELINE_ACTIVITYWATCH_HOSTNAME=<省略時はWindowsのsocket hostname>
```

endpointは`http://127.0.0.1:5600`にコード上で固定する。任意URL、user info、proxy、redirectは受け付けない。HTTP clientは環境のproxy設定を継承せず、redirectを追わない。`localhost`の名前解決やIPv6差を避けるため接続先はliteral loopbackを使う。

### 3.2 bucket discoveryとhost / device境界

1. `/api/0/info`でserver応答と最小互換性を確認する。version文字列だけで可否を決めず、必要endpointのshapeをcontractとして検証する。
2. `/api/0/buckets/`の各entryから`id`、`type`、`client`、`hostname`、`created`だけを読む。未知fieldは許容し、必須field欠落はそのbucketを不正として数える。
3. configured hostnameとmetadataの`hostname`がordinal case-insensitiveで一致するbucketだけを候補にする。bucket ID末尾のhostname抽出は行わない。
4. `currentwindow`をwindow source、`afkstatus`をAFK source、`web.tab.current`を任意Web sourceとして選ぶ。既知client名は診断に使えるが、typeとevent shapeを優先する。
5. upgrade等で同じhost / typeのbucketが複数ある場合は、対象期間にeventがあるbucketをすべて入力へ含める。重複periodはbucket `created`の新しい方、同値ならbucket IDの昇順で決定し、二重加算しない。
6. hostnameごとに一つのWindows Deviceを作る。Device IDは`activitywatch-device-v1 + normalized hostname`のSHA-256からdeterministic ULIDを生成し、hostnameそのものをIDへ露出しない。
7. hostnameが変わった場合は自動mergeせず別Deviceとする。旧Deviceの履歴は残る。

bucket ID、hostname、ActivityWatch event IDはローカルDBの取込状態またはopaque fingerprint生成にのみ使う。API response、通常log、UI statusへbucket IDを返さない。

### 3.3 active Session生成規則 `activitywatch_session_v1`

正規化はpure functionとして実装し、入力event順や同値eventの順番に結果が左右されないようにする。

1. timestampをUTC aware datetimeとしてparseし、duration秒を整数millisecondへhalf-upで丸める。
2. durationが0以下、timestampが不正、終了が開始より前、`app` / `status`が期待shapeでないeventをinvalid countへ加え、そのeventだけを除外する。1件の不正値を理由にpayload内容をlogしない。
3. window、AFK、Web eventを`start_ms`、`end_ms`、bucket priority、event IDでstable sortする。
4. `status == "not-afk"`のperiod unionを作る。未知statusはAFKでもactiveでもないため利用時間へ含めない。
5. window periodとnot-afk periodのintersectionだけをactive fragmentにする。AFK gapを埋めず、同じappの前後Sessionを跨いで結合しない。
6. 同時刻にwindow eventが重複した場合は§3.2のbucket priority、開始時刻、event IDで一つを選び、durationを二重加算しない。
7. active windowが既知browser mappingに一致する場合だけ、同期間のWeb eventをdetail候補にする。Web eventを単独のAppSessionにしない。
8. window / AFK / Webの境界、privacy後のdetail値、UTC日境界でfragmentを分割する。
9. 隙間なく接するfragmentが同じapp、同じprivacy後detail、同じUTC日に属する場合だけ再結合する。正のgapは補完しない。
10. 1ms以上のfragmentをAppSessionとして保存する。`duration_ms`は常に`ended_at_ms - started_at_ms`とする。

App masterのWindows identifierは`activitywatch_app_identifier_v1`で生成する。

- `app`をUnicode NFKC、trim、連続空白の1文字化、casefoldした値をnatural keyにする。
- pathらしい値でもbasenameを推測して削らず、ActivityWatchが返したapp label全体を正規化する。
- 空文字、control characterだけ、255文字超はinvalidとしてSessionを作らない。
- `display_name`はtrimとcontrol character除去後の元labelを使い、同じidentifierの最新取込値で更新できる。
- App IDは`windows + normalized identifier`からdeterministic ULIDを生成する。

AppSession IDはfragment開始時刻をULIDのtime部分にし、device ID、window source fingerprint、fragment開始・終了、app identifier、privacy policy versionから作るSHA-256の先頭80bitをrandomness部分に使う。同じchunk再取込は同じIDを生成する。`source`は`activitywatch`に固定する。

### 3.4 window title / URL privacy

privacy modeは収集後の表示filterではなく、SQLiteへ書く前のdata minimization規則である。

| mode               | 非browser window title | browser title                                    | URL                                                             |
| ------------------ | ---------------------- | ------------------------------------------------ | --------------------------------------------------------------- |
| `app_only`（既定） | 保存しない             | 保存しない                                       | 保存しない                                                      |
| `titles`           | 保存する               | 保存しない                                       | 保存しない                                                      |
| `web`              | 保存する               | activeなWeb eventが非incognitoの場合だけ保存する | activeなWeb eventが非incognitoの場合だけsanitized URLを保存する |

titleはUnicode NFKC、trim、control character除去を行い、最大500文字とする。超過部分は切り詰め、切り詰め件数だけをdiagnosticへ残す。title本文はlogしない。

URLは以下の`activitywatch_url_v1`で削減する。

- `http` / `https`だけを受け付け、userinfo、query、fragmentを必ず除去する。
- hostをIDNA ASCII・lowercaseへ正規化し、default portを除去する。
- pathは保持するが、連続slashを勝手に意味変更せず、control characterを拒否し、全体を最大2,048文字にする。
- `file:`、`data:`、`javascript:`、browser extension、browser内部page、parse不能URLはnullにする。
- `incognito == true`はtitleとURLをnullにする。値が欠落またはbooleanでないeventはWeb detailとして採用しない。
- sanitized URLはTimelineにtext表示するだけで、自動link化、favicon取得、preview、外部requestを行わない。

`titles` modeではbrowser windowのtitleを保存しない。window watcherだけではincognitoを確実に判定できないためである。`web` modeでもWeb eventとactive browser windowが対応しない期間はbrowser titleをwindow watcherから補完しない。

privacy modeを狭めた場合、既存の詳細は自動で全期間削除されない。CLIの`--from` / `--to`付き再生成を行うと、対象UTC日を現在modeでtransactional replaceし、不要になったtitle / URLを削除できる。実装完了時にこの手順をREADMEへ記載する。

### 3.5 UTC日chunk、cursor、late update

ActivityWatch heartbeatは進行中eventのdurationを延長し得るため、取得時点の最終eventを永久確定しない。取込単位をUTC半開区間`[00:00, next 00:00)`へ固定し、chunk全体を再生成可能にする。

- 初回自動取込は現在UTC日を含む直近7 UTC日を対象とする。それ以前は明示CLIでbackfillする。
- 現在時刻の2分前をwatermarkとし、それより後のevent fragmentを確定保存しない。
- scheduled runごとに現在UTC日と前UTC日をrolling refreshする。進行中heartbeat、遅延したAFK / Web event、前日末の境界を更新できる。
- cursorは完全に取得・正規化・commitできたclosed UTC日の末尾だけを表す。現在日refreshでcursorを未来へ進めない。
- catch-upは古いclosed UTC日から順に行い、1 run最大8 UTC日または8分で終了する。残りは次回runへ残す。
- event APIは1 requestあたり`limit=10000`を指定する。ちょうど上限件数が返った区間は欠落の可能性があるため時間区間を半分に再分割する。
- 再分割は最小5分まで行う。5分区間が上限へ達した場合は`too_many_events`としてchunk全体を失敗させ、replaceとcursor更新を行わない。
- API queryには対象chunkの前後5分をcontextとして追加し、境界を跨ぐeventを取得してからUTC日 / watermarkへclipする。

対象UTC日のwindow、AFK、必要なWeb requestがすべて成功し、全responseのshapeを検証できた後だけDB transactionを開始する。transaction内で同Deviceかつ`source = "activitywatch"`の対象日開始fragmentと対応detailを削除し、新しい正規化結果を挿入し、import stateを更新する。途中失敗時はrollbackし、以前の完全な日chunkを表示し続ける。

### 3.6 schedule、lease、retry

Phase 6ではAndroid WorkManagerを使用しない。FastAPI lifespanで起動する`ActivityWatchImportScheduler`が、Backend稼働中の定期実行を担当する。Desktop Packaging前でも通常の`uvicorn`起動で利用でき、停止期間はActivityWatch原本から起動時にcatch upする。

- enabled時だけ、Backend起動30秒後に最初のimportを要求する。
- 通常間隔は15分とする。時刻どおりの実行は保証せず、前回run終了後からdelayを測る。
- transient failureは1分から指数backoffし、最大15分とする。連続失敗数と次回予定だけをDBへ保存する。
- invalid config、missing bucket、incompatible schemaはpermanent扱いとし、15分ごとの再発見は行うが短いretry loopを作らない。
- scheduler、手動API、CLIは同じDB lease `activitywatch_import_v1`を取得する。lease TTLは15分で、UTC日commitごとにheartbeatする。
- lease token一致時だけreleaseし、process deathで残ったleaseはTTL後に回収する。未来へ30分以上ずれたleaseもstaleとして回収できる。
- 1 process内ではasync lock、process間ではSQLiteのconditional updateを正とする。`uvicorn --workers`、reload、CLI近接実行でも二重replaceしない。
- cancellationを通常failureへ変換せず、shutdownでは新しいchunkを開始しない。commit中のtransactionは完了またはrollbackしてから終了する。

手動診断APIはscheduleを増やさず、同じunique runnerへ実行要求を渡す。既にrunning / queuedなら202と現在状態を返す。API request自体で長時間importを待たない。

### 3.7 固定値

| 項目                   | Phase 6の値                 | 理由                                                |
| ---------------------- | --------------------------- | --------------------------------------------------- |
| ActivityWatch endpoint | `http://127.0.0.1:5600`固定 | loopback外への機微データrequestとSSRFを防ぐ         |
| enabled既定            | `false`                     | 外部source取込を明示opt-inにする                    |
| detail mode既定        | `app_only`                  | title / URLを必要になるまで複製しない               |
| schedule               | 起動30秒後、以後15分        | ActivityWatch側に原本があるため高頻度pollを避ける   |
| transient backoff      | exponential 1分、最大15分   | 短い停止から復旧しつつbusy loopを防ぐ               |
| HTTP timeout           | connect 2秒、read 60秒      | server停止を早く検知し、大きいlocal queryは許容する |
| redirect / proxy       | 無効                        | loopback境界を維持する                              |
| initial lookback       | 7 UTC日                     | 初回負荷をboundedにし、過去分は明示backfillにする   |
| settlement lag         | 2分                         | 進行中heartbeatの即時確定を避ける                   |
| rolling refresh        | 現在UTC日 + 前UTC日         | late eventと日境界を更新する                        |
| query context          | chunk前後5分                | 境界を跨ぐeventを正しくclipする                     |
| API event limit        | 10,000件 / request          | 無制限responseを避ける                              |
| 最小split区間          | 5分                         | dense dataで無限再分割しない                        |
| run budget             | 最大8 UTC日または8分        | API processを長時間占有しない                       |
| lease TTL              | 15分                        | 8分budgetとcommit余裕を持たせる                     |
| title上限              | 500 Unicode characters      | UI / DB肥大化を抑える                               |
| sanitized URL上限      | 2,048 characters            | 異常payloadによる肥大化を防ぐ                       |
| Session algorithm      | `activitywatch_session_v1`  | 再生成規則をversion化する                           |
| URL policy             | `activitywatch_url_v1`      | privacy規則変更を追跡可能にする                     |

これらは実装PRのfixtureとpolicy testで固定する。ActivityWatchのserver / watcher versionは実装開始時のstable releaseとWindows実機を確認し、個人のhostnameやbucket IDを文書へ記録せずtoolchainsへ互換範囲だけを書く。

## 4. Backend component設計

### 4.1 component境界

```text
app/activitywatch/
├── client.py          # loopback HTTP、endpoint shape、timeout
├── schemas.py         # ActivityWatch外部responseのstrict minimal model
├── discovery.py       # host / bucket選択とpriority
├── periods.py         # interval union / intersection / clip
├── privacy.py         # title / URL削減
├── normalizer.py      # activitywatch_session_v1
├── importer.py        # fetch → normalize → UTC日replace
├── scheduler.py       # lifespan、backoff、unique trigger
└── status.py          # safe diagnostic DTO

app/repositories/activitywatch.py
├── state / lease
├── UTC日replace
└── desktop detail query

app/cli/import_activitywatch.py
└── dry-run / range backfill / replace
```

HTTP、period演算、privacy、DB transaction、scheduleを分ける。unit testはActivityWatch processやwall clockなしで各境界を検証し、integration testだけがfake loopback serverとSQLiteを結ぶ。

`httpx`をBackend runtime dependencyへexact pinする候補とし、実装開始時にPython 3.13、ActivityWatch stable server、既存lockとの互換性を確認する。公開済み`aw-client`を採用する場合も、remote URL、write API、query helperを無制限に持ち込まず、このAdapter interfaceの内側へ閉じ込める。直接REST実装と`aw-client`のどちらを採用しても、外部shape fixtureと正規化結果を同一にする。

### 4.2 read-only REST境界

許可するmethod / pathは以下だけである。

```http
GET http://127.0.0.1:5600/api/0/info
GET http://127.0.0.1:5600/api/0/buckets/
GET http://127.0.0.1:5600/api/0/buckets/{percent-encoded-id}/events?start=...&end=...&limit=10000
```

- bucket IDはdiscovery response由来の値だけをpercent encodeして使用し、path traversalやcontrol characterを拒否する。
- `POST`、`PUT`、`DELETE`、heartbeat、import / export APIをclient interfaceに実装しない。
- response bodyはstreamingで最大32 MiB / requestまで読み、超過はpermanent protocol errorにする。
- JSON top-level、必須field type、finite duration、timezone付きtimestampを検証する。未知fieldは将来互換のため無視する。
- HTTP 408、429、5xx、timeout、connection errorはtransient、400 / 404はdiscovery再実行後も続く場合permanent protocol error、redirectは拒否する。
- raw response、URL、title、bucket IDをexception messageへ埋め込まない。errorはresult code、status code class、件数だけにする。

### 4.3 browser対応表

active browser判定はActivityWatch公式canonical queryのapp name / regexを参照し、`activitywatch_browser_mapping_v1`としてpure dataへ固定する。初版はWindows上のChrome / Chromium、Edge、Firefox、Brave、Opera、Vivaldiを対象にし、大小文字、`.exe`、既知display nameを正規化後identifierで判定する。

未知browser appは通常のAppSessionとして保存するが、Web eventを結び付けない。利用者のURLを手掛かりにbrowser判定を学習したり、誤結合を自動修正したりしない。mapping追加はfixture、privacy review、version更新を伴う。

### 4.4 clockとevent ID

- ActivityWatch timestampはUTCとしてparseし、offset付き値もUTC epoch msへ変換する。timezone offsetがない値は推測せずinvalidにする。
- server current timeとの差を使ってevent時刻を補正しない。Windows clock異常はsafe diagnosticへ`clock_out_of_range`として残す。
- event IDはbucket内でのみ意味を持つため、`SHA-256(bucket_id + event_id)`のopaque fingerprintへ変換する。
- `desktop_session_details.source_event_id`にはwindow eventと、分割に寄与したAFK / Web event fingerprintから作る`aw1_` prefixのhashを保存する。元bucket IDやinteger IDは保存しない。
- event IDが欠落する互換responseではtimestamp、duration、正規化data、bucket fingerprintからfallback fingerprintを決定的に作る。

## 5. PC保存設計

### 5.1 Alembic `0004_activitywatch`

既存`app_sessions`、`devices`、`apps`は変更せず再利用する。新規tableは次の2つとする。

```text
desktop_session_details
---------------------------------
session_id          TEXT PK FK app_sessions.id ON DELETE CASCADE
window_title        TEXT NULL
url                 TEXT NULL
source_event_id     TEXT NOT NULL
algorithm_version   TEXT NOT NULL
privacy_mode        TEXT NOT NULL
created_at_ms       INTEGER NOT NULL
```

制約:

- `algorithm_version = 'activitywatch_session_v1'`
- `privacy_mode IN ('app_only', 'titles', 'web')`
- `window_title`はnullable、最大500文字をRepositoryで検証する
- `url`はnullableで、保存時に`http://`または`https://`、userinfo / query / fragmentなしを再検証する
- 参照先AppSessionは`source = 'activitywatch'`かつWindows device / appでなければならない。cross-table条件はRepositoryでtransaction内検証する
- `source_event_id`へindexを付け、raw identifierを含めない

```text
activitywatch_import_states
---------------------------------
source_key                 TEXT PK
device_id                  TEXT FK devices.id
algorithm_version          TEXT NOT NULL
privacy_mode               TEXT NOT NULL
completed_through_ms       INTEGER NULL
last_attempt_at_ms         INTEGER NULL
last_success_at_ms         INTEGER NULL
last_result_code           TEXT NULL
consecutive_failures       INTEGER NOT NULL DEFAULT 0
next_eligible_at_ms        INTEGER NULL
lease_token                TEXT NULL
lease_expires_at_ms        INTEGER NULL
created_at_ms              INTEGER NOT NULL
updated_at_ms              INTEGER NOT NULL
```

`source_key`はnormalized hostnameとcollector versionのhashでありhostnameを露出しない。`last_result_code`はallowlistされたcodeだけを保存する。title、URL、app name、bucket ID、exception本文はstateへ保存しない。

Migration testは0003 fixture DBへ既存Android Session、media、location、visitを入れ、upgrade後も全件とindexが保持されることを確認する。downgradeで新tableを落とすことはできるが、通常運用のrollback手段とは扱わない。migration前にdata root全体をbackupする。

### 5.2 transactional replace

`ActivityWatchRepository.replace_utc_day()`は一つのtransactionで次を行う。

1. lease tokenと対象source_keyを再確認する。
2. DeviceとApp masterをUPSERTする。既存Android appとplatformを跨いでmergeしない。
3. 対象Device、`source = 'activitywatch'`、fragment開始時刻が対象UTC日に入るAppSessionを削除する。detailはFK cascadeで同時に消える。
4. 正規化済みAppSessionとdetailを古い順・ID順に挿入する。
5. 同じID / 同じ内容は冪等、同じID / 異なる内容はalgorithm bugとしてrollbackする。
6. closed dayなら`completed_through_ms`を単調増加させる。rolling current dayではcursorを変更しない。
7. success時刻、result code、failure count、lease heartbeatを更新してcommitする。

fetch中にDB transactionを開いたままにしない。1 UTC日ごとに短いtransactionへ分け、Timeline読取りが長時間blockされないようにする。replace後の件数、総duration、app数は記録できるが、app名やdetail値はlogしない。

### 5.3 CLI

```powershell
cd backend
uv run python -m app.cli.import_activitywatch --dry-run
uv run python -m app.cli.import_activitywatch --from 2026-09-01 --to 2026-09-08 --timezone UTC --dry-run
uv run python -m app.cli.import_activitywatch --from 2026-09-01 --to 2026-09-08 --timezone UTC
```

- 引数なしはschedulerと同じinitial / cursor / rolling対象を使う。
- `--from` / `--to`は利用者のtimezone日を受け付けるが、内部では重なるUTC日chunkへ展開する。`to`はexclusiveとする。
- rangeは最大31日 / commandとし、それ以上は分割を求める。
- `--dry-run`はActivityWatchから取得・検証・正規化し、日別件数、総duration、invalid / redacted件数だけを表示する。DB、cursor、leaseを変更しない。
- 書込みrunはdry-runと同じ結果をUTC日単位でreplaceする。title、URL、hostname、bucket ID、app名をconsoleへ出さない。
- ActivityWatch unavailable、missing AFK、schema incompatibilityはnon-zero exitにし、既存DBを変更しない。
- scheduler実行中は`lease_busy`で終了し、強制unlock optionは提供しない。stale leaseは通常規則で回収する。

## 6. Query / API設計

### 6.1 Timeline

既存`GET /api/v1/timeline?date=...&timezone=...`の`app_session` itemを後方互換に拡張する。

```json
{
  "type": "app_session",
  "id": "01...",
  "deviceId": "01...",
  "deviceName": "Windows PC",
  "platform": "windows",
  "appId": "01...",
  "appIdentifier": "code.exe",
  "appName": "Code.exe",
  "source": "activitywatch",
  "startedAt": "2026-09-15T00:00:00Z",
  "endedAt": "2026-09-15T00:18:00Z",
  "durationMs": 1080000,
  "desktopDetail": {
    "windowTitle": "life-timeline",
    "url": null
  },
  "display": {
    "startedAt": "...",
    "endedAt": "...",
    "durationMs": 1080000,
    "continuesFromPreviousDay": false,
    "continuesToNextDay": false,
    "endsAtDayBoundary": false
  }
}
```

- Android AppSessionと既存fixtureには`desktopDetail: null`を返す。
- `app_only`またはredaction対象ではdetail objectを省略せず、`windowTitle` / `url`がnullのobjectを返してsourceの存在を区別できるようにする。
- `desktop_session_details.source_event_id`、algorithm version、privacy modeはpublic Timeline APIへ返さない。
- 日付clip、DST、`display` duration、stable sortの既存規則を変えない。
- 同時刻のsort priorityはAppSession内で既存のdevice ID / ID順を維持し、ActivityWatchだけ特別に先頭へ出さない。

### 6.2 Statistics / PC利用時間

既存`GET /api/v1/statistics/apps`は`app_sessions`を共通集計するため、ActivityWatch Session保存後にWindows app別利用時間へ自動反映される。Phase 6ではresponseへplatform totalsを追加する。

```json
"platformTotals": [
  {"platform": "android", "usageMs": 3600000, "sessionCount": 8},
  {"platform": "windows", "usageMs": 7200000, "sessionCount": 12}
]
```

- intervalをquery rangeへclipしてから集計する既存規則を維持する。
- PC利用時間はAFK除外後の記録されたSession合計であり、wall-clock elapsedやWindows uptimeではない。
- source間で重複するSessionを推測してdedupeしない。AndroidとWindowsは別device利用として加算する。
- URL / domain別統計、window title別統計はPhase 7へ送る。

### 6.3 collector status / manual trigger

```http
GET  /api/v1/activitywatch/status
POST /api/v1/activitywatch/import
```

status responseは次だけを返す。

```json
{
  "enabled": true,
  "detailMode": "app_only",
  "state": "idle",
  "lastResult": "success",
  "lastAttemptAt": "...",
  "lastSuccessAt": "...",
  "completedThrough": "...",
  "nextAttemptAt": "...",
  "webDetailsAvailable": false
}
```

`state`は`disabled`、`idle`、`queued`、`running`、`needs_attention`のallowlistとする。server version、hostname、bucket ID、event / app件数、title、URL、exception本文を返さない。POSTはbodyなし、同じschedulerへunique requestを渡し202を返す。disabled時は409、設定 / schema不備はstatusで説明可能なsafe codeへする。

## 7. React UI

### 7.1 Timeline card

- Windows / ActivityWatchのAppSessionは既存app名、identifier、device、durationをそのまま表示する。
- `desktopDetail.windowTitle`がある場合はapp名の下へ1行、最大2行の折返しで表示する。HTMLとして解釈しない。
- sanitized URLがある場合はhost + pathをplain textで表示し、anchorにしない。copy button、favicon、外部previewは追加しない。
- detailがnullでもcardは壊れず、`PC / ActivityWatch`として識別できる。
- 長いUnicode、RTL、絵文字、制御文字相当fixtureでlayoutとescapingを確認する。
- Android Session、Photo、PlaceVisit、Map deep linkの既存表示を回帰させない。

### 7.2 Dashboard

- 全体利用時間に加えAndroid / Windows別の利用時間とSession数を表示する。
- app rankingは既存の共通listを維持し、platform labelで同名Android / Windows appを区別する。
- Windows 0件、Android 0件、両方0件でも明示的に0を表示する。
- PC利用時間の説明に「ActivityWatchのnot-afk記録から算出」と表示し、OS uptimeと誤解させない。

### 7.3 ActivityWatch状態

Timeline画面の診断領域に、enabled、最終成功、取込済み期間、次回予定、Web詳細可否を表示する。「今すぐ取り込む」はPOST後にstatusをpollし、二重clickでもrunを増やさない。

状態本文はsafe result codeを日本語へ変換する。接続不可時はActivityWatch起動と`127.0.0.1:5600`を確認する案内を出すが、remote公開やfirewall開放を勧めない。privacy mode変更はBackend環境変数と再生成CLIのREADME節へ案内する。

## 8. 既存機能との互換境界

- Androidの`POST /api/v1/sync/app-sessions` contract、Room、WorkManager、lease、retryを変更しない。
- ActivityWatch collectorはAndroid Sync APIを経由せず、Backend内部Repositoryへ保存する。
- `app_sessions.source = 'android_usage_stats'`と`activitywatch`を同じID namespaceで扱い、platform / device FKで混同を防ぐ。
- Photo / LocationのAPI、batch、thumbnail file、PlaceVisit rebuild、Map queryを変更しない。
- ActivityWatch schedulerはFastAPI lifespanのPC taskであり、Android unique workをenqueue / cancel / replaceしない。
- Timelineのhalf-open timezone rangeと表示clipを再利用し、取込chunkのUTC日と利用者の表示日を混同しない。
- Statisticsは共通AppSessionだけを集計し、desktop detailの有無でdurationを変えない。
- `lifelog.db`と`thumbnails/`の既存backup単位を維持する。ActivityWatch取込済み履歴はDBに含まれ、ActivityWatch原本は含まれないことをREADMEへ明記する。
- API healthはActivityWatchの停止に連動して503にしない。collector statusを別endpointで確認する。
- CI / fixture / screenshotへ実在hostname、bucket ID、window title、URL、app一覧を追加しない。

## 9. Issue / PR分割と依存関係

各IDは原則1 Pull Request程度とし、親Issue `Phase 6: ActivityWatch`の子Issueとして管理する。

```text
P6-01 contract・privacy・依存versionを確定
   ├─→ P6-02 Alembic 0004・Repository・lease ──────────┐
   └─→ P6-03 loopback REST client・bucket discovery ──┤
                                                        ↓
                         P6-04 period変換・Session正規化
                                                        ↓
                         P6-05 UTC日import・cursor・CLI
                                                        ↓
                         P6-06 lifespan schedule・status API
                              ├─→ P6-07 Timeline / Statistics API
                              └─→ P6-08 React Timeline / Dashboard / 診断UI
                                                        ↓
                         P6-09 E2E・性能・CI gate
                                                        ↓
                         P6-10 Windows実機受け入れ・運用手順
                                                        ↓
                         P6-11 上位文書更新・Phase 7引き継ぎ
```

### P6-01: contract、privacy境界、依存versionを確定する

- **目的:** ActivityWatchの不安定な外部shapeと機微情報の扱いを、実装前にversion化する。
- **作業:** stable ActivityWatchをWindows専用環境で確認し、info / bucket / currentwindow / afkstatus / web eventの合成fixtureを作る。`httpx`または`aw-client`の採否とexact versionを決定し、§3の固定値とprivacy modesをpolicy testへ落とす。
- **成果物:** source contract、privacy review、合成fixture、dependency / lock更新、toolchains追記。
- **完了条件:** fixtureに個人情報がなく、write API・remote endpoint・query / fragment保存を実装しない境界がtest可能である。

### P6-02: Alembic 0004、Repository、DB leaseを実装する

- **目的:** PC detailとimport状態を既存Factへ安全に関連付ける。
- **依存:** P6-01。
- **作業:** §5の2 table、model、validation、UTC日replace、conditional lease、0003→0004 migration testを実装する。
- **成果物:** migration、SQLAlchemy model、ActivityWatchRepository、state / lease test。
- **完了条件:** Android / Photo / Location dataを保持してupgradeでき、日chunk replaceがall-or-nothingで、stale leaseを回収できる。

### P6-03: loopback REST clientとbucket discoveryを実装する

- **目的:** ActivityWatch実装差をAdapter内へ閉じ込める。
- **依存:** P6-01。
- **作業:** allowlist path、proxy / redirect無効、timeout、response上限、minimal schema、host / bucket discovery、複数bucket priority、retry分類を実装する。
- **成果物:** ActivityWatchClient interface、fake transport test、safe error codes。
- **完了条件:** loopback以外へrequestできず、server停止・404・5xx・巨大body・不正JSON・未知fieldを決定的に分類できる。

### P6-04: active periodとDesktop Session正規化を実装する

- **目的:** AFKを除いたPC利用を、再実行可能な共通AppSessionへ変換する。
- **依存:** P6-01、P6-03。
- **作業:** period union / intersection、overlap priority、browser overlay、UTC日split、app normalization、deterministic IDs、title / URL privacyをpure functionで実装する。
- **成果物:** `activitywatch_session_v1`、`activitywatch_url_v1`、table-driven unit test。
- **完了条件:** 入力順をshuffleしても同じ結果になり、AFK / overlapを二重加算せず、incognito・query・fragment・browser titleが規則どおり保存されない。

### P6-05: UTC日import、cursor、backfill CLIを実装する

- **目的:** Backend停止やheartbeat更新から安全にcatch up / rebuildする。
- **依存:** P6-02〜04。
- **作業:** context fetch、10,000件時split、watermark、initial lookback、rolling refresh、8日 / 8分budget、transactional replace、dry-run / range CLIを実装する。
- **成果物:** ActivityWatchImporter、CLI、chunk / rollback / cursor test。
- **完了条件:** response途中失敗では既存日とcursorが変わらず、同じrange再実行でID・件数・durationが一致する。

### P6-06: lifespan scheduleと診断APIを実装する

- **目的:** Backend稼働中は手動操作なしで取り込み、必要時に安全に状態確認する。
- **依存:** P6-02、P6-05。
- **作業:** FastAPI lifespan、起動30秒、15分schedule、1〜15分backoff、shutdown cancellation、unique manual trigger、status GET / import POSTを実装する。
- **成果物:** ActivityWatchImportScheduler、status schema / API、fake clock test。
- **完了条件:** disabled時はrequestせず、複数app instance / click / CLIがleaseで直列化され、ActivityWatch停止でもhealthと既存APIが動く。

### P6-07: Timeline / Statistics APIへ統合する

- **目的:** PCとAndroidの行動を同じqueryと集計で返す。
- **依存:** P6-02、P6-05。
- **作業:** detail outer join、response schema、platform totals、stable sort、timezone / DST clipを追加する。
- **成果物:** 拡張Timeline / Statistics contract、Backend test、OpenAPI更新。
- **完了条件:** Android既存responseの意味を変えず、Windows Session、nullable detail、日跨ぎ、0件を正しく返す。

### P6-08: React Timeline、Dashboard、診断UIを実装する

- **目的:** PCアプリ利用と許可された詳細を安全に閲覧できるようにする。
- **依存:** P6-06、P6-07。
- **作業:** TypeScript union、PC detail、plain-text URL、platform totals、collector status、manual trigger、error / empty / loading UIを実装する。
- **成果物:** React component / test、responsive style、accessibility確認。
- **完了条件:** title / URLをHTMLやlinkとして解釈せず、Android / Photo / Visit表示を回帰させず、keyboardだけで診断操作できる。

### P6-09: E2E、性能、CI gateを完成する

- **目的:** fake ActivityWatchからSQLite、API、browserまでをrequired checkで回帰検出する。
- **依存:** P6-05〜08。
- **作業:** test用loopback fake server、dense / multi-day fixture、migration、schedule、実HTTP、Playwrightを既存workflowへ統合し、benchmarkを記録する。
- **成果物:** Backend integration、pc-core-e2e拡張、performance記録、安全なfailure artifact。
- **完了条件:** 既存6 checksを維持し、7日initial import fixtureが目標時間内、再取込後の件数が不変、artifactに機微値がない。

### P6-10: Windows実機受け入れと運用手順を完成する

- **目的:** 実ActivityWatchのwatcher、heartbeat、AFK、browser extension、再起動を確認する。
- **依存:** P6-06〜09。
- **作業:** 専用data rootと合成用アプリ / Webページで§11の通常系を実施し、個人履歴なしで`docs/development/phase6-acceptance.md`と運用手順を作る。
- **成果物:** 受け入れ記録、起動 / 停止 / backfill / privacy mode変更 / backup手順。
- **完了条件:** PC / Androidが同じTimelineへ表示され、停止復旧と再取込の冪等性を確認し、未実施の長期・version差はPASSにしない。

### P6-11: 上位文書を更新しPhase 7へ引き継ぐ

- **目的:** 実装値、制約、日常運用をrepository全体で一致させる。
- **依存:** P6-10。
- **作業:** README、overview、product-spec、architecture、technical-design、data-model、implementation-plan、toolchainsを更新し、旧称`desktop_sessions`を共通AppSession + detailへ統一する。
- **成果物:** Phase 6完了状態の文書とPhase 7 backlog。
- **完了条件:** install済み前提、opt-in、AFK、privacy mode、loopback、catch-up、非保証、backup範囲が追跡できる。

## 10. テスト・CI計画

### 10.1 pure unit test

- ISO 8601 UTC / offset、fractional second、duration秒からmillisecondへの丸め。
- 0 / 負duration、NaN / Infinity、timezoneなし、必須data欠落、巨大文字列。
- not-afk union、window intersection、AFK gap、部分overlap、境界接触、完全包含。
- 複数window bucketのpriority、同時event、入力shuffle、stable output。
- Web eventがactive browser windowだけへ付くこと、未知browserでは付かないこと。
- `app_only` / `titles` / `web`、incognito、Web event欠落時のbrowser title抑止。
- URL userinfo / query / fragment除去、IDNA、default port、file / data / browser内部URL拒否。
- NFKC、casefold、空白、control character、長いtitle / URL。
- UTC日境界split、watermark clip、DST表示日は既存queryで正しくclipされること。
- deterministic Device / App / Session / source fingerprintとhash collision扱い。
- 10,000件ちょうどで区間をsplitし、5分上限で安全に失敗すること。
- retry classifier、1〜15分backoff、8日 / 8分budget、cancellation。

### 10.2 migration / Repository test

- 0003 DBの全既存Factを保持した0004 upgrade。
- detail FK、cascade、nullable fields、privacy mode / algorithm validation。
- UTC日replace成功、0件日での正しい置換、途中exception rollback。
- 別Device / Android / 別source Sessionを削除しないこと。
- 同一ID / 同一内容の冪等、同一ID / 異内容のrollback。
- cursorの単調増加、rolling current dayで非更新、失敗時非更新。
- lease初回取得、同時拒否、heartbeat、token不一致release、TTL / future clock recovery。
- DB close / reopen、scheduler / CLI近接実行、SQLite busy classification。
- app natural keyがWindows内で収束し、Android appとmergeしないこと。

### 10.3 HTTP client / importer integration test

- fake loopback serverのinfo、bucket、window、AFK、Web responseを実HTTPで取得する。
- proxy環境変数が設定されてもloopback direct接続し、redirectを拒否する。
- configured hostnameだけを選び、複数hostを混在させない。
- multiple bucket upgrade期間で重複durationを作らない。
- 404、408、429、500、timeout、connection refused、不正JSON、32 MiB超を分類する。
- Web bucket欠落はAppSession成功、AFK bucket欠落はchunk失敗となる。
- initial 7日、closed day catch-up、today / yesterday refresh、2分watermark。
- 2 request目失敗、normalize失敗、DB busyで既存日とcursorが不変。
- 同じrangeの2回実行とActivityWatch heartbeat延長後のrefresh。
- dry-runがDB file、cursor、leaseを変更しない。

### 10.4 Backend API test

- Android / Windows / ActivityWatch SessionがTimelineでstable sortされる。
- ActivityWatch detailあり / null / detail recordなしを後方互換に返す。
- title / URLがresponseにそのままJSON escapeされ、source fingerprintは返らない。
- Statisticsの全体、platform、app totalsがclip後durationと一致する。
- UTC、Asia/Tokyo、DST timezone、日跨ぎ、空日、invalid timezone。
- statusのdisabled / idle / queued / running / needs_attentionとsafe code。
- manual POSTのunique enqueue、disabled 409、実行をHTTP request中に待たないこと。
- ActivityWatch unavailableでもhealth、Timeline、Photos、Map、Android Sync APIが成功する。

### 10.5 Frontend / Playwright

- PC AppSessionのapp名、Windows、device、duration、任意title / URL表示。
- URLがanchorでなく、`<script>`、quote、RTLをtextとして扱う。
- platform totalsと0件表示、同名Android / Windows appの識別。
- collector status、retry、manual triggerの二重click防止、再取得。
- deep link、date / timezone、browser history、Photo / Map navigationの回帰。
- fake ActivityWatch → import → SQLite → Timeline / Dashboardの実DB E2E。
- 再import後のTimeline item数、PC利用時間、IDが不変。
- E2Eから実ActivityWatch、外部URL、faviconへrequestしない。

### 10.6 性能基準

合成fixtureで次をrequiredまたは記録対象にする。

| 規模                                                | 目標                                   |
| --------------------------------------------------- | -------------------------------------- |
| window 20,000 + AFK 5,000 + Web 20,000 events / 7日 | importer pure変換が30秒以内            |
| 7日initial import + SQLite replace                  | Windows CIで120秒以内                  |
| 1日rolling refresh                                  | Windows CIで15秒以内                   |
| Timeline 2,000 mixed items                          | API p95相当500ms以内、Frontend操作可能 |

CI shared runner差を考慮し、microsecond単位の閾値は置かない。閾値を超えた場合はevent本文をartifact化せず、件数、chunk時間、query数、DB row数だけで診断する。必要なindexは実query planとbenchmarkに基づき0004または後続migrationへ追加する。

### 10.7 CI gate

- `frontend-ci`
- `backend-ci (ubuntu)`
- `backend-ci (windows)`
- `android-ci`
- `pc-core-e2e`
- `android-instrumentation-ci`

既存6 checksをrequiredのまま維持する。ActivityWatch unit / integration / scheduler / migrationはBackend CI、fake serverからbrowserまでの確認は`pc-core-e2e`へ入れる。Android codeを変更しなくても既存2 Android checksを外さない。test 0件、skip、fake server未起動を成功扱いしない。

failure artifactはJUnit、safe result code、HTTP status class、event件数、redacted件数、chunk時間、DB row数だけを含める。raw JSON、hostname、bucket ID、event ID、app name、window title、URL、source fingerprint、data root絶対pathを含めない。

## 11. Windows実機受け入れと完了条件

### 11.1 専用環境

1. clean checkoutで既存6 required checks相当を実行する。
2. Phase 6専用`LIFE_TIMELINE_DATA_DIR`へmigrationを適用し、既存Phase 5合成fixtureをseedする。
3. ActivityWatch stable releaseを公式配布物からinstallし、server、window watcher、AFK watcherをloopbackで起動する。
4. Web詳細を確認するシナリオだけ、専用browser profileへ公式Web watcherを導入する。
5. OS hostname、bucket ID、普段のapp / pageを記録せず、Calculator、Notepad、合成localhost page等の専用対象だけを使用する。
6. Backendへenabledとprivacy modeを設定し、Frontendを起動する。
7. 実機記録へversion、result code、件数、duration差、実施日時だけを残す。

### 11.2 通常系シナリオ

1. `app_only`で専用アプリを順番に操作し、起動時または15分schedule後にWindows AppSessionがTimelineへ表示される。
2. keyboard / mouseを操作しないAFK期間を作り、その期間がPC利用時間へ加算されない。
3. Androidの合成AppSessionとWindows Sessionが同じ日・timezoneのTimelineへstableに表示される。
4. Backendを停止してActivityWatchだけで記録し、Backend再起動後に手動操作なしでcatch upする。
5. ActivityWatchを停止してもBackend healthと既存履歴が利用でき、再起動後にretryで復旧する。
6. 「今すぐ取り込む」を連続操作してもrunとSessionが重複しない。
7. 同じ期間をCLIで再取込し、ID、件数、PC利用時間が変わらない。
8. `titles`で非browser合成titleだけが表示され、browser window titleは保存されない。
9. `web`でquery / fragment付きlocalhost URLを開き、保存 / 表示値からquery / fragmentが除去される。
10. Web watcherなし、またはincognito eventのfixtureではbrowser detailがnullでもAppSessionが残る。
11. privacy modeを`app_only`へ戻し、対象期間をCLI再生成して既存title / URLがDBとAPIから消える。
12. 0003 snapshotから0004へupgradeし、既存Android / Photo / Location / Visit表示が変わらない。

### 11.3 任意の拡張シナリオ

- 7日を超える31日backfillの処理時間とDB増加量。
- Windows sleep / resume、ActivityWatch / Backendの起動順序逆転。
- ActivityWatch upgradeでbucketが複数になった期間の重複防止。
- Windows timezone変更、DST timezone表示、clock修正。
- `uvicorn --reload`、複数worker、CLIを近接実行したlease挙動。
- title exclusion設定、Web watcher version差、複数browser同時利用。
- 24時間以上の通常利用でのcursor lag、scheduler回数、DB growth。

任意シナリオの未実施はPhase 6通常系の失敗とはしないが、PASSとも記録しない。実施した結果が設計前提と異なる場合はalgorithm / browser mapping / compatibility tableを更新してから完了扱いにする。

### 11.4 受け入れチェックリスト

| ID    | 完了条件                                                                            | 主な証拠                       |
| ----- | ----------------------------------------------------------------------------------- | ------------------------------ |
| AC-01 | Phase 5までの6 required checksと主要画面が回帰していない                            | CI、既存E2E                    |
| AC-02 | ActivityWatch連携は既定OFFで、OFF時にREST requestを行わない                         | Settings / client test         |
| AC-03 | REST接続先がliteral loopback、read-only allowlistに固定される                       | client security test           |
| AC-04 | metadataから同一hostnameのwindow / AFK / Web bucketを選択する                       | discovery fixture              |
| AC-05 | AFK periodがPC利用時間へ含まれず、window overlapを二重加算しない                    | normalizer test、実機          |
| AC-06 | Web eventはactive browser windowだけへ関連付く                                      | period / browser fixture       |
| AC-07 | privacy modeごとのtitle / URL保存範囲が固定される                                   | policy test、DB確認            |
| AC-08 | URLのuserinfo、query、fragment、unsafe schemeとincognito detailを保存しない         | privacy test                   |
| AC-09 | Device / App / Session IDが再実行で安定する                                         | deterministic ID test          |
| AC-10 | UTC日replaceがtransactionalで、途中失敗時に既存日とcursorを維持する                 | Repository / integration test  |
| AC-11 | 初回7日、today / yesterday refresh、closed day cursor、watermarkが規則どおり動く    | fake clock importer test       |
| AC-12 | Backend / ActivityWatch停止後に起動時catch-upとretryで復旧する                      | 実機停止復旧記録               |
| AC-13 | scheduler、manual trigger、CLIがleaseで直列化される                                 | concurrency test、実機近接操作 |
| AC-14 | 同一range再取込でSession数、ID、duration、統計が増殖しない                          | DB / API / E2E                 |
| AC-15 | WindowsとAndroidのSessionが同じTimelineでtimezoneどおり表示される                   | Playwright、実機               |
| AC-16 | DashboardがAndroid / Windows別利用時間をclip後durationから表示する                  | Statistics / React test        |
| AC-17 | status UIがsafe codeだけを表示し、手動取込をuniqueに要求できる                      | API / UI test                  |
| AC-18 | migrationが既存AppSession、Photo、LocationPoint、PlaceVisitを保持する               | 0003→0004 test                 |
| AC-19 | CI、log、artifact、受け入れ記録に実在の機微情報がない                               | artifact / document review     |
| AC-20 | READMEと運用手順からenable、privacy変更、backfill、停止復旧、backup境界を再現できる | Phase 6受け入れ記録            |

AC-01〜20の自動検証、通常系Windows実機確認、運用文書をPhase 6完了条件とする。24時間長期運転、全ActivityWatch version、全browser、sleep / clockの組合せは任意確認・非保証として分離する。

## 12. セキュリティとprivacy確認

- ActivityWatchへは`127.0.0.1:5600`からread-only GETだけを送り、LAN bind、Tailscale公開、Funnel、認証無効化を案内しない。
- HTTP proxy、redirect、任意endpointを無効にし、bucket IDのpath escapeを防ぐ。
- 連携は既定OFF、detailは既定`app_only`とし、title / Web履歴の複製をユーザーが選べる。
- query / fragment / userinfo、incognito Web detail、browser内部URL、file pathを保存しない。
- stored title / URLはReactでplain textとして扱い、HTML、Markdown、command、linkとして実行しない。
- ActivityWatch raw eventをlife-timelineのlog / artifact / fixtureへcopyしない。fixtureは予約domainと架空titleだけで手作りする。
- exception、metrics、status、CLIはsafe codeと件数だけを扱う。
- `lifelog.db`にはPC行動履歴が増えるため、Phase 5の位置情報と同等の機微データとしてdata root backupを保護する。
- APIは現行どおりloopback FastAPI + Tailscale Serve境界を維持する。ActivityWatch detail用の新しい外部serviceを追加しない。
- detail modeを狭めた後の再生成、data root backupからの復旧で古いdetailが戻り得ることを運用手順へ明記する。

## 13. 非保証と運用上の注意

- ActivityWatchやwatcherが停止していた期間、watcherが権限不足だった期間、原本から削除済みの期間はlife-timelineで復元できない。
- 15分scheduleはdeadlineではない。Backend停止、sleep、負荷、backoffにより表示まで遅延する。
- PC利用時間はActivityWatchのwindow eventと`not-afk` eventが重なる記録時間であり、Windows uptime、勤務時間、請求時間、実際の集中時間を保証しない。
- AFK timeoutはActivityWatchのユーザー設定に依存する。life-timeline側で180秒へ揃えない。
- `audible` tab、動画再生、download、background tabは、inputがAFKなら初版のPC利用時間に含めない。
- Web watcher未導入、未対応browser、browser mapping不一致、incognito、extension停止中はURL / browser titleを表示できない。
- URLからquery / fragmentを削除しても、path自体にtoken、document名、個人識別子が含まれる場合がある。`web` modeはそのriskを理解して選ぶ。
- hostname変更は別Deviceになる。自動mergeしない。
- ActivityWatch REST APIはversion間で変更され得る。shape非互換時は誤取込より安全な停止を選ぶ。
- 初回自動取込は7日だけである。古い履歴は31日以下のrangeに分けてCLI backfillする。
- life-timeline DBからSessionを消してもActivityWatch原本は消えず、再取込で復元し得る。ActivityWatch側削除は本Phaseの責務外である。
- detail mode変更は過去全期間へ自動適用されない。必要範囲をCLIで再生成する。

## 14. リスクと対策

| リスク                                       | 対策                                                          | 受け入れでの確認             |
| -------------------------------------------- | ------------------------------------------------------------- | ---------------------------- |
| AFK時間をwindow durationへ加算する           | not-afk intersectionを必須にし、AFK bucket欠落時は取込停止    | period fixture、実機AFK      |
| 複数bucketで利用時間が二重になる             | metadata groupとstable overlap priority                       | upgrade fixture              |
| 進行中heartbeatが後から延びる                | 2分watermarkとtoday / yesterday rolling replace               | fake clock、実機継続event    |
| API上限で古いeventがsilent欠落する           | 10,000件ちょうどで時間split、最小区間超過はchunk失敗          | dense fixture                |
| 一部request失敗後に日データを消す            | fetch / normalize完了後に1日transactional replace             | injected failure test        |
| scheduler / CLIが同時replaceする             | token付きDB lease、TTL、process内lock                         | concurrency test             |
| hostnameやbucket変更で重複Deviceを作る       | normalized hostname単位、変更は意図的に別Device               | discovery test、文書化       |
| app label差でAppが増殖する                   | NFKC / trim / casefoldのversioned natural key                 | table-driven test            |
| browser eventを非active tabへ結び付ける      | active windowとのintersectionとversioned mapping              | browser fixture              |
| incognitoやsecret URLを複製する              | 既定app_only、incognito null、query / fragment / userinfo削除 | privacy test、DB review      |
| window titleがXSSになる                      | plain text render、React escaping、CSP回帰                    | malicious fixture E2E        |
| ActivityWatch停止でBackend全体が不健康になる | collector statusをhealthから分離し、failure isolation         | unavailable integration test |
| API version変更で誤parseする                 | minimal strict schema、unknown field許容、非互換時safe stop   | contract fixture             |
| 大量backfillがAPI / SQLiteを占有する         | 31日CLI上限、8日 / 8分run、日transaction                      | performance test             |
| privacy mode変更後も旧detailが残る           | range rebuild CLIと確認queryを運用手順へ記載                  | mode縮小シナリオ             |
| log / CIへ個人履歴が出る                     | safe code / countのみ、合成fixture、artifact review           | AC-19                        |

## 15. Phase 7への引き継ぎ

Phase 6完了時に次をPhase 7 Product Polishへ引き渡す。

- Android / Windows共通AppSessionと、PC固有detailを分ける保存・query境界。
- ActivityWatch raw原本からUTC日単位で再生成できるversioned normalizer。
- Backend lifespan上のlocal collector schedule、DB lease、cursor、retry、safe statusの実装pattern。
- app / platform別利用時間と、Timeline上のWindows detail表示。
- detailを既定で最小化し、privacy mode縮小時に再生成する運用。
- 個人のapp、title、URL、hostnameを含めないfixture / log / artifact / acceptance形式。

Phase 7で検討する候補:

- App / Location / Photo / ActivityWatchのfilter、期間検索、URL domain検索。
- category masterの初期値、手動分類、ActivityWatch category規則との関係。
- 日 / 週 / 月Statistics、platform比較、domain別集計、Calendar badge。
- collector Settings UI、privacy mode変更preview、対象期間redaction / delete。
- hostname変更・PC移行時のDevice merge UI。
- Backup / Export UIにおけるPC detailの選択的除外。
- Timelineの大量Session grouping、連続appの折りたたみ、virtualization。
- manual eventとPC / Android活動の横断表示。

Phase 7では検索や分類のためにtitle / URLを無条件収集へ切り替えない。現行privacy modeを前提に、detailがnullでもすべての画面と集計が成立する設計を維持する。Phase 8でWindows service / system tray /自動起動を追加する場合も、ActivityWatch RESTのloopback read-only境界、DB lease、起動時catch-upを置き換えず再利用する。

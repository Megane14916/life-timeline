# Phase 6 ActivityWatch privacy review

この文書は、P6-01でActivityWatchの外部データをlife-timelineへ複製する範囲と、複製前に適用するprivacy境界を記録します。ActivityWatch本体の原本を変更したり、個人の履歴をrepositoryへ保存したりしません。

## 1. レビュー結果

| 項目 | P6-01の決定 |
| --- | --- |
| 収集開始 | `LIFE_TIMELINE_ACTIVITYWATCH_ENABLED=true` の明示opt-in時だけ |
| 接続先 | `http://127.0.0.1:5600` のliteral loopbackに固定 |
| HTTP | read-onlyのGET 3種類だけ。proxyとredirectは無効 |
| 原本 | ActivityWatchのbucket、event、内部DBへwriteしない |
| 保存先 | 共通`app_sessions`と、後続migrationで追加するdetailへ最小限だけ保存 |
| 既定privacy | `app_only`。window title、browser title、URLを保存しない |
| URL | `activitywatch_url_v1`でscheme、userinfo、query、fragment、incognitoを検証・削減 |
| 診断 | result code、件数、durationなどの集計だけ。payload、hostname、bucket ID、title、URL、app labelを出さない |
| fixture | `contracts/activitywatch-v1.json`。予約ドメインと架空ラベルのみ |

## 2. 保存範囲

privacy modeはUI表示時のfilterではなく、SQLiteへ書く前のdata minimizationです。

| mode | 非browser window title | browser title | sanitized URL |
| --- | --- | --- | --- |
| `app_only` | 保存しない | 保存しない | 保存しない |
| `titles` | 保存する | 保存しない | 保存しない |
| `web` | 保存する | active browserかつ`incognito=false`のWeb eventだけ | 同じ条件で`http` / `https`の安全なpathだけ |

すべてのmodeで、AFK中のwindow fragmentはAppSessionへ含めません。Web event単体からAppSessionを作らず、対応するactive browser windowとのintersectionがある場合だけdetail候補にします。未知browser、対応しないevent shape、`incognito`がbooleanでないeventはWeb detailへ関連付けません。

### title

- Unicode NFKC、trim、control character除去を行う。
- 最大500 Unicode characters。超過は本文を記録せず、切り詰め件数だけをsafe diagnosticへ残す。
- `app_only`ではSQLiteへ渡す前に破棄する。

### URL

- `http` / `https`だけを受け付ける。
- userinfo、query、fragment、default portを除去する。
- hostはIDNA ASCIIかつlowercaseへ正規化する。
- `file:`、`data:`、`javascript:`、browser内部page、extension page、parse不能URLは`null`にする。
- 最大2,048 charactersを超える値は`null`またはpolicy errorとして扱い、元の値を保存・表示しない。
- sanitized URLもlink化、favicon取得、preview、外部requestを行わずplain textとして扱う。

## 3. 脅威と対策

| 脅威 | 対策 | P6-01で固定した検証 |
| --- | --- | --- |
| remote ActivityWatchへの誤接続 | endpointをliteral loopbackへ固定し、任意URLを設定にしない | contractの`baseUrl`とallowed request |
| bucketへの破壊的操作 | GET以外をclient境界へ含めない | forbidden method test |
| proxy / redirect経由の境界逸脱 | proxy継承とredirect追従を無効にする | contract policy test |
| URL queryやuserinfoの複製 | URL policyで削除し、unsafe schemeを拒否する | synthetic URL fixture |
| incognito detailの複製 | `incognito=true`をtitle / URLとも`null`にする | privacy mode test |
| XSSや個人情報のlog混入 | plain text表示、payload非記録、safe result codeのみ | logging boundary review |
| ActivityWatch API変更による誤解釈 | version文字列だけで判断せず、minimal shapeをfixtureで固定する | source contract |

## 4. fixtureの取り扱い

`contracts/activitywatch-v1.json`は、後続のREST clientとnormalizerが参照する手作りの契約fixtureです。`fixture-host`、`Fixture Editor.exe`、`[fixture]`ラベル、`example.invalid`はすべてテスト専用の値です。個人のhostname、bucket ID、アプリ名、window title、URL、ActivityWatch raw exportは追加しません。

fixtureにはprivacy処理を検証するため、query / fragment付きURL、userinfo付きURL、incognito eventも含めます。これらは実データではなく、保存前に削除またはnull化される入力の例です。

## 5. 運用境界

privacy modeを狭めた場合、過去のdetailをrolling refreshだけで自動削除しません。`--from` / `--to`付きの対象期間再生成で、現在のmodeによるtransactional replaceを行います。このCLIと削除確認の手順はP6-04以降でREADMEへ追加します。

ActivityWatch停止時は新しい取込を行わず、既存のlife-timeline Session、cursor、healthを維持します。実機受け入れでは専用data rootを使用し、実際の履歴、URL、hostname、bucket ID、raw log、画面画像をIssue、PR、CI artifactへ持ち込みません。

## 6. 参照

- [Phase 6詳細実装計画](../detailed_plan/phase6-activitywatch.md) §1〜4 / P6-01
- [ActivityWatch REST API](https://docs.activitywatch.net/en/latest/api/rest.html)
- [ActivityWatch Releases](https://github.com/ActivityWatch/activitywatch/releases)
- [P6-01 Issue #118](https://github.com/Megane14916/life-timeline/issues/118)

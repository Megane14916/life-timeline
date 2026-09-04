# Phase 0 詳細実装計画: Project Setup

- 作成日: 2026-09-04
- 対象: monorepo、Frontend、Backend、Android、開発ツール、CI、開発手順
- 状態: 実装前の計画
- 完成条件: 三つのプロジェクトを新しいcheckoutから再現でき、ローカルとPull RequestのCIでformat・lint・型チェック・test・buildが成功する

## 1. 参照資料とPhase 0の位置付け

| 文書 | Phase 0に関係する方針 | 本計画への反映 |
| --- | --- | --- |
| [overview.md](../overview.md) | Android + Windows、PC中心、クラウドを必須にしない | Windowsで再現できるローカル開発基盤とAndroidプロジェクトを作る |
| [product-spec.md](../product-spec.md) | Android CompanionとPC Web UIを段階的に実装する | 三つのプロジェクトを独立してbuildできるmonorepoにする |
| [architecture.md](../architecture.md) | React + TypeScript + Vite、FastAPI、Kotlin + Compose + Room等 | Phase 0では各プロジェクトの最小起動点を作り、機能依存は利用するPhaseで追加する |
| [technical-design.md](../technical-design.md) | Ruff・型チェック・pytest、ESLint・Vitest、Android Lint・Gradle、GitHub Actions | ローカルコマンドとPR必須CIを同じ検証内容に揃える |
| [data-model.md](../data-model.md) | 正規化データをSQLiteへ保存する | schema・SQLite・Alembic migrationはPhase 1へ残し、配置と依存管理の受け皿だけを用意する |
| [implementation-plan.md](../implementation-plan.md) | repository、monorepo、三つの初期化、formatter / linter、最小CI、docs | 現状を確認し、未完了項目を作業単位と受け入れ条件へ分解する |
| [phase1-pc-core.md](phase1-pc-core.md) | Phase 1の開始条件、想定ディレクトリ、全PR必須の実DB E2E | Phase 1が既存構成を作り直さず着手できる成果物とCI拡張点を用意する |

計画作成時点で、Git repositoryとGitHubの`origin`、README、設計文書は存在する。`frontend/`、`backend/`、`android/`、`.github/workflows/`はまだ存在しない。そのため「GitHub repository作成」は完了済みとして再作成せず、remote・default branch・PR運用に必要な設定を確認する。

## 2. ゴールと実装範囲

### 2.1 到達する状態

```text
life-timeline/
├── frontend/     React + TypeScript + Vite
├── backend/      Python + FastAPI
├── android/      Kotlin + Jetpack Compose
├── docs/
├── .github/workflows/
└── repository共通設定

local Windows                    Pull Request
├ Frontend test / build          ├ frontend-ci
├ Backend test / import / start  ├ backend-ci
└ Android test / APK build       └ android-ci
```

Phase 0は、機能を実装するための再現可能な土台を作るフェーズである。各プロジェクトには起動・テスト可能な最小コードを置くが、ライフログの収集・保存・同期・表示は実装しない。

### 2.2 実装するもの

- 既存GitHub repositoryと`main`を利用するmonorepo構成。
- OS・CIで同じ版を選べるtoolchain指定とlockファイル。
- React + TypeScript + Viteの最小Frontend。
- Python + FastAPIの最小Backend。
- Kotlin + Jetpack Composeの最小Androidアプリ。
- 各プロジェクトのformatter、linter、型チェック、unit / smoke test、buildコマンド。
- GitHub ActionsのPR必須CI。
- 実データ・秘密情報・生成物を追跡しないrepository共通設定。
- Windowsでのセットアップ・起動・検証手順と、CIの運用手順。

### 2.3 後続フェーズへ残すもの

| 対象 | 実施時期 |
| --- | --- |
| SQLAlchemy、Alembic、SQLite接続、正規化schema、seed | Phase 1 |
| Timeline / Statistics API、Dashboard、日付処理 | Phase 1 |
| 実SQLite → API → ReactのPlaywright E2E | Phase 1の最初のPC Core実装PRから全PR必須 |
| Room、UsageStats、AppSession生成・ULID、手動同期、Tailscale | Phase 2 |
| WorkManager、自動retry、pending / syncing / synced | Phase 3 |
| 写真・位置情報・ActivityWatch等の機能依存 | 対応するPhase |
| Tauri、常駐サービス、配布用release build / signing | Phase 8またはリリース準備時 |

Phase 0では将来使うという理由だけでRoom、WorkManager、Retrofit、SQLAlchemy、Alembic、Playwright等を先行導入しない。依存は最初に利用するPhaseで追加し、そのPRのテストで利用実績を持たせる。

## 3. 開始条件と事前決定

### 3.1 開始条件

- `origin`が意図したGitHub repositoryを指し、`main`がdefault branchであることを確認する。
- 作業開始時の既存変更を確認し、ユーザーの文書変更を上書きしない。
- Windows上でGit、PowerShell、Android SDKを利用できる。CIで必要なSDKはworkflow内でセットアップする。
- 実装タスクごとに技術設計§14に従ってIssueを作成する。大きなbootstrapは親Issueと子タスクで追跡する。

### 3.2 Toolchain基準を最初に固定する

バージョン番号は実装開始時に公式のサポート状況と相互互換性を確認して決め、同じPRで以下へ記録する。本計画書に、未検証の将来変わりうる番号は固定しない。

| 対象 | 選択方針 | repositoryでの固定方法 |
| --- | --- | --- |
| Python | FastAPI等が対応する安定版のminorを1つ選ぶ | `.python-version`、`pyproject.toml`の`requires-python`、`uv.lock` |
| Python依存管理 | `uv` | `pyproject.toml`を定義元、`uv.lock`をcommit。CIはlockと同期する |
| Node.js | 実装時点のActive LTSから1つのexact versionを選ぶ | `.nvmrc`、`package.json`の`engines`、`package-lock.json` |
| Frontend package manager | npm | CIは`npm ci`を使い、lockと不一致なら失敗させる |
| JDK | 採用するAndroid Gradle Pluginが要求・対応するLTS JDK | CI設定と開発手順に同じmajorを記載する |
| Android SDK / plugin | 実装時点で公式にサポートされる組み合わせ | Gradle version catalog / build設定、Gradle Wrapper |

- version rangeだけで環境を表現せず、CIが使うexact versionまたはlockの解決結果を残す。
- lockファイルとGradle WrapperをGit管理し、開発者ごとのグローバル依存解決に頼らない。
- toolchainを更新するPRでは三つのCIをすべて実行する。
- Androidの`namespace` / `applicationId`は初期化前に1つ決め、READMEへ記録する。後続Phaseで安易に変更しない。
- Androidの`minSdk`はUsageStats等のMVP要件と採用ライブラリを満たす値、`compileSdk` / `targetSdk`は実装時点の要件を確認して固定する。値と選定理由をIssueまたは開発手順へ残す。

### 3.3 採用する検証ツール

| 対象 | Format | Lint / 静的検証 | Test | Build |
| --- | --- | --- | --- | --- |
| Backend | Ruff format | Ruff check、mypy | pytest | package importと起動smoke |
| Frontend | Prettier | ESLint、`tsc --noEmit` | Vitest + React Testing Library | Vite production build |
| Android | Spotless + ktlint | Android Lint | JUnit | Gradle debug APK |

自動修正コマンドとCI用checkコマンドを分ける。CIではファイルを書き換えず、不一致をエラーとして報告する。

## 4. Monorepo構成

```text
life-timeline/
├── .github/
│   └── workflows/
│       ├── backend-ci.yml
│       ├── frontend-ci.yml
│       └── android-ci.yml
├── backend/
│   ├── app/
│   │   ├── __init__.py
│   │   └── main.py
│   ├── tests/
│   │   └── test_health.py
│   ├── pyproject.toml
│   └── uv.lock
├── frontend/
│   ├── src/
│   ├── public/
│   ├── package.json
│   ├── package-lock.json
│   ├── tsconfig*.json
│   └── vite.config.ts
├── android/
│   ├── app/
│   ├── gradle/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── gradlew
│   └── gradlew.bat
├── docs/
│   ├── detailed_plan/
│   └── ...
├── .editorconfig
├── .gitattributes
├── .gitignore
├── .nvmrc
├── .python-version
└── README.md
```

- `frontend`・`backend`・`android`は独立したbuild単位とする。rootのnpm workspaceへPythonやGradleを無理に統合しない。
- sourceとtestを各プロジェクト内に置き、rootには製品コードを置かない。
- Gradle WrapperのJARとpropertiesは再現に必要なため追跡する。
- IDE固有設定は原則追跡しない。共有価値のある設定を追加する場合は、個人パス・拡張機能必須化・秘密情報を含めない。
- ディレクトリ名とimport起点はPhase 1計画の`backend/app`、`frontend/src`と一致させる。

## 5. Repository共通設定

### 5.1 `.editorconfig`

- UTF-8、final newline、末尾空白除去を共通指定する。
- Markdownの意図した行末空白を壊さない設定を検討し、現在の文書を一括整形しない。
- Python / TypeScript / Kotlinのindentを言語ごとに明示する。

### 5.2 `.gitattributes`

- source・設定・文書はLFをrepository上の基準とする。
- `gradlew.bat`等のWindows batchはCRLF、画像・APK等はbinaryとして扱う。
- 既存文書の改行だけを変更する巨大diffをbootstrap PRへ混ぜない。必要な正規化は別Issueで行う。

### 5.3 `.gitignore`

最低限、以下を対象にする。

```text
# shared secrets / local overrides
.env
.env.*
!.env.example

# product data
data/
*.db
*.db-wal
*.db-shm

# Python
.venv/
__pycache__/
.pytest_cache/
.mypy_cache/
.ruff_cache/

# Node / Vite / Playwright
node_modules/
frontend/dist/
test-results/
playwright-report/

# Android / Gradle / IDE
.gradle/
**/build/
local.properties
.idea/
*.iml

# OS
.DS_Store
Thumbs.db
```

`.env.example`を置く場合は秘密値を含めず、各変数の用途と安全な開発用値だけを記載する。Phase 0の最小アプリに環境変数が不要なら空の雛形は作らない。

### 5.4 GitHub設定

- Pull Requestで三つのCI workflowを起動し、固定したjob名をrequired checksにする。
- workflowには原則としてpath filterを置かず、docsのみの変更でも土台が壊れていないことを確認する。
- `main`への直接pushを通常運用にせず、必須check成功後にmergeするbranch protection / rulesetを設定する。
- Actionsへ長期秘密情報を登録しない。Phase 0のCIはrepository contentsのread権限で実行できる構成にする。
- fork由来のPRでも秘密情報なしで検証できるようにする。

## 6. Frontend初期化

### 6.1 最小アプリ

- ViteのReact + TypeScriptテンプレートを基に`frontend/`を作る。
- strictなTypeScript設定を有効にし、型エラーをproduction build前に独立して検出する。
- 画面は`life-timeline`というアプリ名と、開発基盤が起動していることを示す静的なplaceholderだけにする。
- Vite dev serverは既定でloopbackにbindし、LAN公開を前提にしない。
- Timeline、Dashboard、Router構成、API client、地図・グラフ・CSS frameworkは必要なPhaseで導入する。
- テンプレートに残る未使用ロゴ・サンプルcounter・外部リンクは削除する。

### 6.2 npm scripts

| script | 内容 |
| --- | --- |
| `dev` | loopbackでVite dev serverを起動 |
| `format` | Prettierで対象ファイルを修正 |
| `format:check` | Prettier差分を検査 |
| `lint` | ESLintを警告も失敗扱いで実行 |
| `typecheck` | `tsc --noEmit` |
| `test` | 開発時のVitest |
| `test:run` | CI用にwatchなしでVitestを1回実行 |
| `build` | TypeScript検証後にVite production build |

テストはplaceholderのアプリ名が表示されることをReact Testing Libraryで確認する。実装と同じ文字列定数を参照するだけのテストにせず、利用者から見える描画を確認する。

## 7. Backend初期化

### 7.1 最小アプリ

- `backend/app/main.py`にFastAPI application factoryまたは明確なapp生成点を置く。
- `GET /api/v1/health`を提供し、`200`と`{"status":"ok"}`を返す。
- healthはDBや外部サービスへ接続せず、Python processとroutingが起動したことだけを示す。
- 開発起動は`127.0.0.1:8000`にbindする。CORS、Tailscale、静的Frontend配信、DB初期化は追加しない。
- import時にファイル作成、ネットワーク接続、環境依存の副作用を起こさない。

### 7.2 `pyproject.toml`とコマンド

- runtime依存とdevelopment依存を分離し、lockへ含める。
- Ruffのformat / lint、mypy、pytestの設定を`pyproject.toml`へ集約する。
- package importとtestで同じimport経路を使い、テストだけの`sys.path`操作を置かない。
- pytestではhealthの正常応答と未知routeの404をTestClient / httpxで確認する。
- CIの起動smokeではサーバーをloopbackで起動し、healthへ実HTTPリクエストを行い、終了コードとprocess cleanupを確認する。

標準コマンド:

```powershell
uv sync --all-groups --frozen
uv run ruff format --check .
uv run ruff check .
uv run mypy app tests
uv run pytest
```

Phase 1でSQLAlchemy・Alembic・tzdataを追加できる構成にするが、Phase 0では空のmodel / repository / migrationディレクトリを先行作成しない。

## 8. Android初期化

### 8.1 最小アプリ

- Kotlin DSLとJetpack Composeを使うsingle-activityアプリを`android/`へ作る。
- 画面には`life-timeline`と、初期化済みであることが分かる静的なplaceholderだけを表示する。
- Material theme、application label、launcher iconはテンプレート依存の名称からlife-timelineへ変更する。
- debug APKを署名不要の標準debug keystoreでbuildできるようにする。release signingは設定しない。
- Internet、Usage Access、Location、Media等のpermissionは、利用する機能のPhaseで目的と説明を伴って追加する。
- Room、WorkManager、Retrofit、UsageStats等の依存や空クラスを先行追加しない。

### 8.2 Gradleと検証

- Gradle Wrapperを唯一のGradle実行経路とし、グローバルGradleを前提にしない。
- plugin / library versionは一か所へ集約する。
- Composeのplaceholderを支える最小unit testを置く。UI instrumentation testはPhase 0の必須ゲートにせず、機能実装時に追加する。
- Android Lintの新規warningを成功扱いにしない。Spotless / ktlintのcheckをCIで実行する。

Windowsの標準コマンド:

```powershell
./android/gradlew.bat -p android spotlessCheck lintDebug testDebugUnitTest assembleDebug
```

CIでは同じGradle taskを`./android/gradlew -p android ...`で実行する。生成したAPKは短期間のCI artifactとして保存し、Gitへcommitしない。

## 9. CI計画

### 9.1 Workflowと固定check名

| check名 | runner | 手順 |
| --- | --- | --- |
| `frontend-ci` | Ubuntu | Node固定版 → `npm ci` → format check → lint → typecheck → unit test → build |
| `backend-ci (ubuntu)` | Ubuntu | Python / uv固定版 → frozen sync → Ruff → mypy → pytest → 起動smoke |
| `backend-ci (windows)` | Windows | 同じlock → Ruff → mypy → pytest → PowerShellで起動smoke |
| `android-ci` | Ubuntu | JDK / Android SDK → Gradle Wrapper validation → Spotless → lint → unit test → debug APK |

- Pull Requestのopen・更新で全checkを実行する。`main`へのmerge後にも同じ検証を行い、default branchの状態を確認する。
- dependency cacheはlockファイルをkeyにする。cache missでも結果が変わらないようにする。
- timeoutを各jobに設定し、サーバーprocessやGradleが停止しない障害を明確に失敗させる。
- warningを隠すための包括的なignoreや`continue-on-error`を使わない。必要な除外は理由と狭い対象を設定に残す。
- workflow内でformatterを実行してcommit内容を書き換えない。checkモードだけを使う。
- APK等のartifact uploadはtest / build成功判定を置き換えない。

### 9.2 CI導入の順序

最初の実装PRで、三つの最小プロジェクト、共通設定、三つのworkflowを同時に追加する。これを **bootstrap PR** とする。

```text
bootstrap PR
  ├ repository共通設定
  ├ Backendの最小起動・test
  ├ Frontendの最小描画・test・build
  ├ Androidの最小描画・test・APK
  └ 全CI workflow
        ↓ 全check成功後にmerge
以後のPhase 0 PR
        ↓ 毎回すべてのrequired checksを実行
Phase 1最初のPC Core PR
        └ 実DB → API → Reactの`pc-core-e2e`を追加しrequiredにする
```

workflowだけを先に追加して存在しないプロジェクトのためにskipさせたり、プロジェクトだけを先にmergeしてCI未導入期間を作ったりしない。bootstrap PRは大きくなるため、親IssueのチェックリストでP0-01〜06の最小部分を追跡する。

Phase 0にはSQLiteと製品APIがないため、DB → API → ReactのE2Eを偽のDBや固定レスポンスで代用しない。Phase 1計画どおり、最初のPC Core実装PRで実経路を作り、`pc-core-e2e`を全PR必須にする。Phase 0ではBackendの実HTTP health smokeとFrontendの実production buildを独立して確認する。

### 9.3 Required checksの運用確認

- bootstrap PRで各checkが少なくとも1回成功し、check名が確定してからrulesetへ指定する。
- Phase 0完了前にrequired checksを意図的に失敗させ、mergeが禁止されることを確認する。
- job名を変更するPRでは先にrulesetとの移行手順を用意し、存在しないcheck待ちでmerge不能になる状態を避ける。
- CI設定変更のPRも変更後のworkflowで三つの検証を通す。

## 10. 実装タスクと依存関係

各IDは計画内の識別子。実装時はGitHub Issueへ目的・作業・完了条件を移す。bootstrap PRには各Issueを関連付ける。

```text
P0-01 Toolchain・共通規約
   ├─→ P0-02 Backend scaffold ─┐
   ├─→ P0-03 Frontend scaffold ├─→ P0-05 CI / bootstrap PR
   └─→ P0-04 Android scaffold ─┘
                                  ↓
                              P0-06 README・開発手順
                                  ↓
                              P0-07 受け入れ確認
```

### P0-01: Toolchainとrepository共通規約を確定する

- **目的:** 開発者とCIが同じ環境・ファイル規約を使えるようにする。
- **作業:** §3.2のversion調査・固定、applicationId決定、`.editorconfig`、`.gitattributes`、`.gitignore`を準備する。
- **成果物:** version指定、共通設定、決定理由の記録。
- **完了条件:** 三つのscaffoldが採用versionで生成・buildできる見通しがあり、秘密情報・DB・生成物が追跡対象外になる。

### P0-02: Backendを初期化する

- **目的:** Phase 1のPC Backendを追加できる、型・test付きのFastAPI最小構成を作る。
- **依存:** P0-01。
- **作業:** `pyproject.toml`、lock、app、health route、Ruff・mypy・pytest、起動smokeを追加する。
- **成果物:** §7のBackendとローカル検証コマンド。
- **完了条件:** clean checkoutでfrozen syncでき、format・lint・型・test・実HTTP healthが成功する。

### P0-03: Frontendを初期化する

- **目的:** Phase 1のTimeline / Dashboardを追加できる、型・test付きのReact最小構成を作る。
- **依存:** P0-01。
- **作業:** Vite React TypeScript scaffold、lock、placeholder、Prettier・ESLint・Vitest・RTL、npm scriptsを追加する。
- **成果物:** §6のFrontendとローカル検証コマンド。
- **完了条件:** `npm ci`からformat check・lint・型・test・production buildが成功し、loopbackでplaceholderを表示できる。

### P0-04: Androidを初期化する

- **目的:** Phase 2以降のCollectorを追加できる、test付きのCompose最小アプリを作る。
- **依存:** P0-01。
- **作業:** Kotlin / Compose project、version管理、Gradle Wrapper、placeholder、Spotless / ktlint、Android Lint、unit testを追加する。
- **成果物:** §8のAndroid appとdebug APK。
- **完了条件:** WindowsとCIで同じWrapperからformat check・lint・unit test・debug buildが成功する。

### P0-05: GitHub Actionsとrequired checksを導入する

- **目的:** 全プロジェクトの最低品質をPRのmerge条件にする。
- **依存:** P0-02〜04の最小成果物。bootstrap PR内では同時に作業する。
- **作業:** 三つのworkflow、cache、timeout、artifact、Ubuntu / Windows smoke、rulesetを設定する。
- **成果物:** §9の固定checkとCI実行記録。
- **完了条件:** bootstrap PRで全checkが成功し、以後のPRでは失敗・未実行のrequired checkがあるとmergeできない。

### P0-06: READMEと開発手順を整備する

- **目的:** 新しいcheckoutから推測せずに環境を再現できるようにする。
- **依存:** P0-02〜05で実際に成功したコマンド。
- **作業:** 必要toolchain、導入、起動、検証、artifactの場所、代表的な失敗の切り分けをREADMEへ記載する。設計文書へのindexも追加する。
- **成果物:** Windows向けQuick Startと各projectのコマンド一覧。
- **完了条件:** 記載コマンドとCIコマンドが対応し、未使用の予定コマンドを成功例として載せていない。

### P0-07: clean checkoutでPhase 0を受け入れる

- **目的:** 開発者の既存cacheやIDE設定に依存していないことを確認する。
- **依存:** P0-01〜06。
- **作業:** §12の手順を新しいcheckoutで実行し、CI・Git状態・起動・生成物を確認する。
- **成果物:** 受け入れチェック結果と、Phase 1へ渡す既知の課題。
- **完了条件:** AC-01〜12を満たし、Phase 1の開始条件を満たす。

## 11. テスト方針

### 11.1 Phase 0で意味のある最小テスト

- Backend: `/api/v1/health`の200とbody、未知routeの404、実processへのhealth request。
- Frontend: 利用者から見えるアプリ名・placeholderの描画、production build。
- Android: 最小の純粋Kotlin unit testとdebug APK build。Compose UIのinstrumentationは機能要件ができたPhaseで追加する。
- 設定: format / lint / typecheckが意図した対象を走査し、サンプルファイル1つだけに限定されていないこと。
- lock: clean checkoutでlockを更新せず依存導入できること。

Phase 0のテストは「フレームワークが導入されている」ことだけでなく、実行入口・routing・描画・buildを壊したときに失敗することを確認する。一方、まだ存在しないDB・同期・Timeline等をmockで先行テストしない。

### 11.2 失敗系の確認

- Frontendの型エラー・lint違反・失敗testの各々で`frontend-ci`が失敗する。
- Backendのformat差分・型エラー・失敗test・起動失敗の各々でBackend checkが失敗する。
- Androidのformat差分・lint違反・失敗test・compile errorの各々で`android-ci`が失敗する。
- lockファイルと定義ファイルが不一致ならfrozen installが失敗する。
- required check失敗中はPRをmergeできない。

確認のために作った意図的な違反は受け入れ前に戻す。失敗させるテストコードをmainへ残さない。

## 12. ローカル確認手順と受け入れ条件

### 12.1 Windowsでの確認順

1. 新しいcheckoutを作り、Git管理外の既存virtual environment・`node_modules`・Gradle cacheに依存しない状態から開始する。
2. README記載のexact / major versionと、実際のPython・uv・Node・npm・JDKを照合する。
3. Backendでfrozen sync、Ruff format check、Ruff lint、mypy、pytestを実行する。
4. Backendを`127.0.0.1:8000`で起動し、実HTTPで`/api/v1/health`の応答を確認して停止する。
5. Frontendで`npm ci`、format check、ESLint、typecheck、Vitest、production buildを実行する。
6. Frontendをloopbackで起動し、placeholderをブラウザで確認する。
7. AndroidでSpotless check、Android Lint、unit test、debug APK buildを実行する。
8. 必要に応じてdebug APKをemulatorまたは実機へinstallし、起動してplaceholderを確認する。APK build成功は必須、install確認はAndroid環境がある受け入れ時に行う。
9. 生成物を確認し、`git status --short`にcache、DB、secret、APK、build outputが現れないことを確認する。
10. Pull Requestで全required checksを通し、意図的失敗時のmerge禁止を確認する。

### 12.2 受け入れチェックリスト

| ID | 完了条件 | 証拠 |
| --- | --- | --- |
| AC-01 | 既存GitHub repository・main・originを正しく利用できる | remote / default branch確認 |
| AC-02 | toolchainと依存がrepository内の指定・lock・Wrapperで再現される | clean checkoutでの導入結果 |
| AC-03 | Frontendがloopbackで起動し、最小画面を表示できる | Component test、build、手動smoke |
| AC-04 | Backendがloopbackで起動し、healthを返せる | API test、実HTTP smoke |
| AC-05 | Androidのdebug APKをbuildできる | Gradle test / assemble結果、CI artifact |
| AC-06 | 三つのformatter / linterとFrontend / Backendの型チェックが有効 | ローカル・CI結果、意図的違反の確認 |
| AC-07 | 三つのunit / smoke testが実行され、テスト0件を成功扱いにしない | test reportとCI log |
| AC-08 | GitHub ActionsがすべてのPRで三つのプロジェクトを検証する | workflow run、固定check名 |
| AC-09 | required checkの失敗・未実行時にmainへmergeできない | ruleset設定と失敗確認 |
| AC-10 | secrets、実データ、DB、cache、build生成物がGit対象外 | `.gitignore`とclean status |
| AC-11 | READMEだけでWindowsのセットアップ・起動・全検証を再現できる | 新しいcheckoutでの手順実行記録 |
| AC-12 | Phase 1が既存構成を作り直さず、DB・API・UI・必須E2Eを追加できる | ディレクトリ・コマンド・CI拡張点のレビュー |

AC-01〜12と全required checksの成功をもってPhase 0を完了する。

## 13. リスクとPhase 1への引き継ぎ

| リスク | Phase 0での対処 | 引き継ぎ |
| --- | --- | --- |
| toolchainが開発者とCIでずれる | version指定、lock、Wrapper、Windows / Ubuntu検証 | 更新PRでも全checkを実行する |
| 最初のPRだけCIが存在しない | scaffoldとworkflowを同じbootstrap PRに含める | merge前に全checkを実際に通す |
| templateの不要コード・設定が設計になる | placeholder以外のサンプルを削除し、最小依存にする | 機能ごとに必要な構造を追加する |
| formatter導入で既存docsに巨大diffが出る | 対象と改行規約を定め、既存文書を一括変更しない | 文書整形は必要なら別Issueにする |
| cacheがないとbuildできない | clean checkoutとcache missでfrozen installを確認する | lock更新を明示的なPRにする |
| ローカルの秘密・DB・APKをcommitする | 共通ignoreとclean status確認 | Phase 1の`data/`も追跡しない |
| 将来依存を先行導入して更新負担が増える | 未使用依存・空の抽象層を置かない | SQLAlchemy等は利用するPhaseで追加する |
| Phase 0のsmokeを製品E2Eと誤認する | health・buildの検証範囲を明記する | Phase 1最初のPRで実DB → API → React E2Eをrequiredにする |
| CI job名変更でrequired checkが待ち続ける | 固定名とrulesetの移行手順を管理する | `pc-core-e2e`追加時も同様に設定する |

Phase 1へは、`backend/app`と`frontend/src`の起動点、固定toolchain、lockファイル、ローカル検証コマンド、全PRで動く既存CIを引き渡す。Phase 1はそこへSQLite・migration・正規化データ・Timeline / Statisticsと実DB E2Eを追加し、Phase 0のcheckを置き換えず維持する。

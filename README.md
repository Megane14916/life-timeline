# Life Timeline

Life Timelineは、PCとスマートフォンから収集した活動データをローカルで管理し、複数の形式で振り返るためのアプリケーションです。

Phase 3まで実装済みです。SQLiteへ正規化データを保存し、AndroidのUsage Accessで収集したAppSessionをRoomへ退避します。WorkManagerが定期収集と自動同期を行い、Tailscale Serve経由でFastAPIへ送信したデータをTimelineとDashboardで表示できます。画面の「収集して同期」は診断・即時実行用に残しています。写真と位置情報は後続Phaseで追加します。

## Repository構成

| パス                 | 内容                                    |
| -------------------- | --------------------------------------- |
| `backend/`           | FastAPI APIとPythonのtest・品質設定     |
| `frontend/`          | React + TypeScript + ViteのWeb UI       |
| `android/`           | Kotlin + Jetpack ComposeのAndroidアプリ |
| `.github/workflows/` | Pull Requestと`main`で実行するCI        |
| `docs/`              | 製品仕様、設計、実装計画                |

## Windows Quick Start

### 1. 必要なtoolchain

次のversionをインストールしてください。採用理由と更新規則は[開発toolchainとプロジェクト識別子](docs/development/toolchains.md)に記載しています。

導入元は[Git for Windows](https://git-scm.com/install/windows)、[uv公式インストール手順](https://docs.astral.sh/uv/getting-started/installation/)、[Node.js Downloads](https://nodejs.org/en/download)、[Eclipse Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17)、[Android Studio](https://developer.android.com/studio/install)を使用します。Node.jsとJDKは各ページで下表のversionを選択してください。

| Tool                    | Version      | 確認コマンド                      |
| ----------------------- | ------------ | --------------------------------- |
| Git                     | 現行の安定版 | `git --version`                   |
| Python                  | `3.13.15`    | `python --version`                |
| uv                      | `0.12.9`     | `uv --version`                    |
| Node.js                 | `24.20.0`    | `node --version`                  |
| npm                     | `11.19.0`    | `npm --version`                   |
| JDK                     | `17`         | `java -version`                   |
| Android SDK Platform    | API `36`     | Android StudioのSDK Managerで確認 |
| Android SDK Build-Tools | `36.0.0`     | Android StudioのSDK Managerで確認 |

uv 0.12.9の公式standalone installerとPython本体は次のコマンドで導入できます。installerの内容を確認してから実行する場合は、上記のuv公式手順を参照してください。

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/0.12.9/install.ps1 | iex"
uv python install 3.13.15
```

Android SDKはAndroid StudioのSDK Managerで`Android SDK Platform 36`と`Android SDK Build-Tools 36.0.0`を追加してください。`ANDROID_HOME`にはAndroid SDKのディレクトリを設定します。Gradleはrepository内のWrapperが取得するため、グローバルインストールは不要です。

### 2. Checkout

```powershell
git clone https://github.com/Megane14916/life-timeline.git
cd life-timeline
```

既にclone済みの場合は、作業ブランチを作る前に`main`を更新します。

```powershell
git switch main
git pull --ff-only
```

### 3. Backend

依存関係をlock fileどおりに導入します。

```powershell
cd backend
uv sync --all-groups --frozen
```

デモ用の保存先をrepository rootから絶対パスで設定し、同じ環境変数のままmigrationとseedを実行します。

```powershell
$env:LIFE_TIMELINE_DATA_DIR = Join-Path (Get-Location).Path '..\data\demo'
New-Item -ItemType Directory -Force -Path $env:LIFE_TIMELINE_DATA_DIR | Out-Null
uv run alembic upgrade head
uv run python -m app.cli.seed --data-dir $env:LIFE_TIMELINE_DATA_DIR
```

DBは`$env:LIFE_TIMELINE_DATA_DIR\lifelog.db`に作成されます。migration前のDBへseedを実行すると失敗するため、必ず同じ保存先へmigrationを適用してください。

開発サーバーをloopbackで起動します。

```powershell
uv run uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

別のPowerShellからhealth endpointを確認します。

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

`status`が`ok`なら起動成功です。検証コマンドは`backend/`で実行します。

```powershell
uv run ruff format --check .
uv run ruff check .
uv run mypy app tests
uv run pytest
uv run python scripts/smoke.py
```

実HTTP smokeは自身で一時サーバーを起動するため、ポート`8000`で動かしている開発サーバーを停止してから実行してください。Windows用wrapperの`./scripts/smoke.ps1`も同じsmokeを実行します。

### 4. Frontend

別のPowerShellでrepository rootから実行します。

```powershell
cd frontend
npm ci
npm run dev
```

ブラウザで次の固定日URLを開きます。

```text
http://127.0.0.1:5173/timeline?date=2026-09-03&timezone=Asia%2FTokyo
```

`npm run dev`はViteを`127.0.0.1:5173`へ固定します。ポートが使用中の場合は別ポートへ移動せず失敗するため、意図しないAPI接続先で起動することはありません。

検証コマンドは`frontend/`で実行します。

```powershell
npm run format:check
npm run lint
npm run typecheck
npm run test:run
npm run build
```

実DBを使うChromium E2EはBackendとFrontendを一時環境で起動し、次で実行します。初回だけブラウザを導入してください。

```powershell
npx playwright install chromium
npm run e2e
```

`npm run format`はPrettierでファイルを修正します。CIと同じ確認には、ファイルを書き換えない`npm run format:check`を使います。Production buildは`frontend/dist/`へ生成されます。

### 5. Android

Android SDKとJDK 17を利用できるPowerShellで、repository rootからWrapperを実行します。

```powershell
./android/gradlew.bat -p android spotlessCheck lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

成功するとdebug APKが次の場所に生成されます。

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Android Studioで実行する場合は`android/`をprojectとして開き、JDK 17とAPI 36を選択してください。実機でPhase 2を確認する場合は、Androidの開発者向けオプションとUSB debuggingを有効にしてから、次のようにinstallします。

```powershell
adb devices
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.megane14916.lifetimeline/.MainActivity
```

初回起動時は`Usage access: 要設定`の「利用状況へのアクセス設定」から`life-timeline`を許可します。アプリへ戻って`Usage access: 許可済み`になったことを確認し、`PC endpoint (HTTPS)`へTailscale ServeのURLを入力して「PC URLを保存」を押します。保存後はWorkManagerが定期収集と自動同期を登録します。アプリの「収集して同期」は収集・送信・ACK反映を即時に確認する診断手段として残り、画面にはschedule、最終収集、最終同期、Pending、最後の状態が表示されます。

Android Chromeで`https://<machine>.<tailnet>.ts.net/api/v1/health`を開き、`{"status":"ok"}`が表示されることを先に確認すると、アプリ設定とSync APIを切り分けやすくなります。実機での全手順と結果は[Phase 2受け入れ記録](docs/development/phase2-acceptance.md)に記載しています。

### 6. Tailscale Serve（Phase 2 / Phase 3）

FastAPIは必ず`127.0.0.1:8000`で起動し、AndroidからはTailscale Serveが提示するHTTPS hostnameへ接続します。Funnel、LANへの直接公開、cleartext HTTP、証明書検証の無効化は使用しません。Windows / Androidの接続確認、最小ACL、障害復旧は[Tailscale Serve接続手順](docs/development/phase2-tailscale.md)を参照してください。

Backend起動中にrepository rootからServeを設定・確認できます。

```powershell
.\scripts\tailscale-serve.ps1 -Action configure -LocalPort 8000
.\scripts\tailscale-serve.ps1 -Action check -Endpoint 'https://<machine>.<tailnet>.ts.net'
```

実tailnetのhostnameやidentityはログ・Issue・PRへ記録しないでください。

## CIとローカル検証の対応

Pull Requestと`main`へのpushでは、変更パスに関係なく次のcheckをすべて実行します。

| Required check         | ローカルで対応する検証                                             |
| ---------------------- | ------------------------------------------------------------------ |
| `frontend-ci`          | `npm ci`、format、lint、typecheck、test、build                     |
| `backend-ci (ubuntu)`  | frozen sync、format、lint、typecheck、test、実HTTP smoke           |
| `backend-ci (windows)` | Ubuntuと同じ検証をPowerShell上で実行                               |
| `android-ci`           | Gradle Wrapper検証、Spotless、Android Lint、unit test、debug build |
| `pc-core-e2e`           | 実DB → Sync API → Timeline / DashboardのE2E                      |
| `android-instrumentation-ci` | Emulator上のRoom / Compose instrumentation test |

Android CIの成功時には`life-timeline-debug-apk`というartifactが保存されます。GitHubのPull Requestで対象checkを開き、workflow runの`Artifacts`から取得できます。保存期間は7日です。

Phase 3では、上記6つをPull Requestのrequired checkにしています。`android-ci`はWorkManager workerのunit test、`android-instrumentation-ci`はRoom / scheduler / WorkManager integration test、`pc-core-e2e`は実DBからTimeline / Dashboardまでを確認します。失敗・未実行・中断の状態ではmergeできません。

## Phase 1固定データの確認値

`2026-09-03` / `Asia/Tokyo`では、Timelineに4件、Dashboardに合計`1時間5分`、4 sessions、2 appsが表示されます。アプリ別はAndroid Chromeが`35分`、Windows Chromeが`30分`です。

`2026-09-04`へ進むと合計`1分30秒`、`2026-09-05`ではTimelineが空、Dashboardの合計・session・appがすべて0になります。日付を戻してreloadしてもURLの日付と両表示が一致します。

## Phase 1受け入れ記録

Windowsで実施した手順、migration・seedの再実行、Backend再起動、停止からの復旧、AC-01〜13、CIとrulesetの結果は[Phase 1受け入れ記録](docs/development/phase1-acceptance.md)に記録しています。

## Phase 2受け入れ記録

Windowsの専用一時DB、Android実機、Tailscale Serveを使ったUsage Access・手動同期・停止からの復旧・再送の確認結果と、AC-01〜15の証拠は[Phase 2受け入れ記録](docs/development/phase2-acceptance.md)に記録しています。実tailnetのhostname、identity、個人のアプリ一覧、tokenは記録しません。

## Phase 3受け入れ記録

WorkManagerによる定期収集・自動同期、Room v2、期限付きlease、retry、unique work、CI gateの実装と正常系の確認は[Phase 3受け入れ記録](docs/development/phase3-acceptance.md)に記録しています。Doze、OEMの電池最適化、端末再起動、Tailscale切替、24時間以上の運転はOS・実機依存のため、必要に応じて同記録の任意シナリオを追加確認します。

## よくある問題

### `uv sync --frozen`が失敗する

`python --version`と`uv --version`を確認してください。`pyproject.toml`と`uv.lock`が一致しない場合も失敗します。依存関係を意図的に変更する作業でのみlockを更新し、通常のセットアップでは`--frozen`を外して解決しないでください。

### `npm ci`がversionまたはlock fileのエラーになる

`node --version`と`npm --version`がQuick Startの値に合っているか確認してください。依存関係を変更するときは`package.json`と`package-lock.json`を同じPull Requestへ含めます。

### `migration未適用`またはDBパスが想定と違う

Backendを実行するPowerShellで保存先を確認します。

```powershell
$env:LIFE_TIMELINE_DATA_DIR
Resolve-Path $env:LIFE_TIMELINE_DATA_DIR
Test-Path (Join-Path $env:LIFE_TIMELINE_DATA_DIR 'lifelog.db')
```

別のPowerShellでBackendを起動する場合は、`LIFE_TIMELINE_DATA_DIR`を設定し直してください。`uv run alembic upgrade head`とseedの`--data-dir`が同じ絶対パスを指す必要があります。

### `npm run dev`でポートが使用中になる

8000番と5173番の使用状況を確認し、不要な開発サーバーを停止してから再実行します。

```powershell
Get-NetTCPConnection -LocalPort 8000,5173 -ErrorAction SilentlyContinue
```

別ポートへ変更する場合は、Vite proxy、Backendの起動引数、手順内URLを同時に変更してください。通常の受け入れ確認では固定値を使います。

### Backendのsmokeでポートエラーになる

ポート`8000`を利用しているprocessを確認します。

```powershell
Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue
```

開発サーバーなどが動いている場合は停止してからsmokeを再実行します。

### Android buildでSDKまたはJavaが見つからない

`java -version`がJDK 17を指していることと、`ANDROID_HOME`が有効なSDKディレクトリを指していることを確認してください。Android StudioのSDK ManagerでAPI 36とBuild-Tools 36.0.0がインストール済みか確認します。

### AndroidでUsage Accessが未許可になる

アプリの`利用状況へのアクセス設定`からAndroidの設定を開き、`life-timeline`を許可してからアプリへ戻ります。許可前は「収集して同期」を実行できません。設定後も`要設定`のままなら、アプリをforegroundへ戻してから再確認するか、Androidの設定画面を再度開いて許可状態を確認します。

### AndroidでPCへ接続できない

PC endpointには`https://`のTailscale Serve URLだけを設定します。まずPCの`http://127.0.0.1:8000/api/v1/health`、次にAndroid Chromeの`https://<machine>.<tailnet>.ts.net/api/v1/health`を確認します。PCのFastAPI、Serve、Tailscaleの順に状態を確認し、Serveを設定したPCとAndroidが同じtailnetに参加していること、ACLで対象PCの443が許可されていることを確認します。Funnel、LANアドレス、`http://`、証明書検証の無効化は使用しません。

### 同期失敗後もPendingが残る

同期失敗時にPendingが減らないことはデータ保持のための正常な動作です。FastAPIまたはTailscale Serveを復旧すると、WorkManagerのretry / 次回triggerが同じSession IDを自動再送します。「収集して同期」は復旧を待たずに状態を確認する診断・即時実行手段です。ACKを受信したSessionだけがsyncedになり、同じSessionを再送してもPC側で重複登録されません。PC停止、Serve停止、Tailscale切断の切り分けは[Tailscale Serve接続手順](docs/development/phase2-tailscale.md)と[Phase 3受け入れ記録](docs/development/phase3-acceptance.md)を参照してください。

### ローカルでは成功するがCIで失敗する

失敗したcheckの最初のerrorを確認し、対応表のローカルコマンドを同じ順序で実行します。生成済みのvirtual environment、`node_modules`、Gradle cacheに依存している疑いがある場合は、新しいcheckoutで再現を確認します。

## ドキュメント

| 文書                                                          | 内容                                |
| ------------------------------------------------------------- | ----------------------------------- |
| [概要](docs/overview.md)                                      | 製品の目的、スコープ、用語          |
| [製品仕様](docs/product-spec.md)                              | 機能要件と利用シナリオ              |
| [データモデル](docs/data-model.md)                            | 一般化した活動データのモデル        |
| [アーキテクチャ](docs/architecture.md)                        | システム構成と責務分割              |
| [技術設計](docs/technical-design.md)                          | API、保存、同期、テスト等の技術方針 |
| [実装計画](docs/implementation-plan.md)                       | Phase構成と実装順序                 |
| [Phase 0詳細計画](docs/detailed_plan/phase0-project-setup.md) | Project Setupのタスクと受け入れ条件 |
| [Phase 1詳細計画](docs/detailed_plan/phase1-pc-core.md)       | PC Coreの実装計画                   |
| [Phase 2詳細計画](docs/detailed_plan/phase2-android-app-usage-mvp.md) | Android App Usage MVPの実装計画 |
| [開発toolchain](docs/development/toolchains.md)               | 固定version、識別子、更新規則       |
| [Phase 0受け入れ記録](docs/development/phase0-acceptance.md)  | clean checkout検証とPhase 1開始判定 |
| [Phase 1受け入れ記録](docs/development/phase1-acceptance.md)  | PC Coreの検証結果とPhase 2への引き継ぎ |
| [Phase 2 Tailscale手順](docs/development/phase2-tailscale.md) | Serve、ACL、障害復旧の手順 |
| [Phase 2受け入れ記録](docs/development/phase2-acceptance.md)  | Android実機、同期、CIの検証結果 |
| [Phase 3詳細計画](docs/detailed_plan/phase3-automatic-sync.md) | 自動収集・自動同期・retryの実装計画 |
| [Phase 3受け入れ記録](docs/development/phase3-acceptance.md) | WorkManager、障害復旧、実機確認の記録 |

Androidの`applicationId`と`namespace`は`com.megane14916.lifetimeline`です。

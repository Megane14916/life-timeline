# 開発toolchainとプロジェクト識別子

## 1. この文書の役割

この文書は、Phase 0以降の開発とCIで使うtoolchainの基準を定める。基準日は2026年9月4日とし、後続Issueではここに記載した値を設定ファイルへ反映する。

バージョンを更新するときは、互換性表とサポート状況を確認し、この文書、ルートのversion指定、lock file、CI設定を同じPull Requestで更新する。

## 2. 採用バージョン

| 対象                  | 採用値       | repositoryでの指定場所                                               | 選定理由                                                                                            |
| --------------------- | ------------ | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Python                | `3.13.15`    | `.python-version`                                                    | bugfixサポート中の3.13系を使い、ライブラリ互換性と保守期間を優先する                                |
| uv                    | `0.12.9`     | この文書、`.github/workflows/backend-ci.yml`                         | CIとローカルで同じresolverを使うため、minor指定ではなく完全なversionを固定する                      |
| Node.js               | `24.20.0`    | `.nvmrc`                                                             | production向けのLTS系列を完全なversionで固定する                                                    |
| npm                   | `11.19.0`    | `frontend/package.json#packageManager`、`frontend/package-lock.json` | Node.js 24.20.0同梱版を使い、lock file形式とのずれを防ぐ                                            |
| JDK                   | `17`         | `android/app/build.gradle.kts`、`.github/workflows/android-ci.yml`   | Android Gradle Plugin 9.3の実行要件に合わせる                                                       |
| Android Gradle Plugin | `9.3.0`      | `android/gradle/libs.versions.toml`                                  | stable releaseを使い、GradleとJDKの組み合わせを公式互換性表に合わせる                               |
| Gradle                | `9.5.0`      | `android/gradle/wrapper/gradle-wrapper.properties`                   | Android Gradle Plugin 9.3の必須versionに合わせる                                                    |
| Kotlin                | `2.4.10`     | `android/gradle/libs.versions.toml`                                  | 基準日時点のstable releaseを使う                                                                    |
| Compose BOM           | `2026.03.00` | `android/gradle/libs.versions.toml`                                  | compile SDK 36をサポートするCompose 1.10.5系列を使う                                                |
| Android compile SDK   | API `36`     | `android/app/build.gradle.kts`                                       | Android 16のAPIでcompileし、現在のGoogle Play target要件と揃える                                    |
| Android target SDK    | API `36`     | `android/app/build.gradle.kts`                                       | 2026年8月31日以降の新規アプリ・更新のGoogle Play要件に合わせる                                      |
| Android min SDK       | API `26`     | `android/app/build.gradle.kts`                                       | 個人利用の初期版ではAndroid 8.0以降を対象にし、バックグラウンド実行制約が導入された世代を下限にする |
| AndroidX WorkManager  | `2.11.2`     | `android/gradle/libs.versions.toml`                                 | 2026-09-11時点のstable。compile SDK 36 / min SDK 26を満たし、Phase 3の定期work・coroutine worker・testingを提供する |

Pythonのpatch version、Node.jsのLTS version、uvのversionは更新頻度が高い。Issue開始時に更新を必要とする理由がなければ、この表の値をそのまま使う。後続Issueの途中で暗黙に最新版へ変更しない。

### Phase 2 Android依存

P2-01では、既存のKotlin・AGP・compile SDKを変更せず、次の依存を追加した。いずれもversion catalogへ固定し、Roomのschema生成や同期clientなど、最初に利用するPhaseの実装から参照する。

| 対象 | 採用値 | 選定理由 |
| --- | --- | --- |
| KSP | `2.3.11` | 公開されている現行stable plugin。KSP 2.3系はKotlin compiler versionと独立したversioningのため、Kotlin `2.4.10`と組み合わせる |
| Room | `2.8.4` | compile SDK 36 / min SDK 26で利用できるstable AndroidX。KSPによるKotlin code generationを使う |
| Kotlin Coroutines | `1.11.0` | Androidのstructured concurrencyと後続のRepository / ViewModelで共通利用するstable release |
| DataStore Preferences | `1.2.1` | device identity・PC URLなど小さな設定をtransactionalに保存するstable release |
| Lifecycle | `2.10.0` | compile SDK 36と両立するstable AndroidX。ComposeのViewModel / lifecycle連携を提供する |
| Retrofit | `3.0.0` | version付きHTTP APIを型安全に定義できるstable release |
| OkHttp | `5.3.0` | RetrofitのtransportとしてTLS接続を最新stable系列へ固定する |
| Kotlin Serialization | `1.11.0` | JSON DTOを明示的にシリアライズし、unknown fieldを拒否する |
| Coroutines Test | `1.11.0` | production Coroutinesと同じversionでunit testを実行する |
| AndroidX Test core / rules / runner | `1.7.0` | Roomのinstrumentation testで、Android API上のDB再起動・transaction境界を検証する |
| AndroidX Test Ext JUnit | `1.3.0` | `AndroidJUnit4` runnerでinstrumentation testを実行する |

依存の一次資料は、[Room release notes](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore release notes](https://developer.android.com/jetpack/androidx/releases/datastore)、[Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle)、[KSP releases](https://github.com/google/ksp/releases)、[Retrofit releases](https://github.com/square/retrofit/releases)、[OkHttp repository](https://github.com/square/okhttp)、[Kotlin serialization documentation](https://kotlinlang.org/docs/serialization.html)、[Kotlin coroutines releases](https://github.com/Kotlin/kotlinx.coroutines/releases)を参照した。

### Phase 3 Android依存

P3-01では、WorkManagerのruntime KTXとtesting helperを追加した。定期workのruntimeは`androidx.work:work-runtime-ktx:2.11.2`、integration test用helperは`androidx.work:work-testing:2.11.2`へ固定する。公式release notesでは2.11.2がstableであり、network constraint、`NetworkStateTracker`、未捕捉例外後のperiodic work再scheduleに関する修正を含む。WorkManagerは`compileSdk 33`以上を要求するため、既存のcompile SDK 36と互換である。

一次資料: [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work)

## 3. Androidアプリの識別子

Androidプロジェクトでは次の値を使う。

| 項目            | 値                             |
| --------------- | ------------------------------ |
| `applicationId` | `com.megane14916.lifetimeline` |
| `namespace`     | `com.megane14916.lifetimeline` |
| 初期package     | `com.megane14916.lifetimeline` |

`applicationId`はインストール済みアプリ、権限設定、署名済み成果物を識別する永続値として扱う。公開後の変更は別アプリとして扱われるため、表示名やrepository名の変更に追随させない。実装上のpackageを分割しても、`applicationId`は維持する。

## 4. version固定の運用

- Pythonはuvがルートの`.python-version`を読む。backendの依存関係はP0-02で`pyproject.toml`と`uv.lock`へ記録し、`uv.lock`をGit管理する。
- Node.jsはversion managerがルートの`.nvmrc`を読む。frontendの依存関係はP0-03で`package.json`と`package-lock.json`へ記録し、両方をGit管理する。
- AndroidはP0-04でJDK toolchain、version catalog、Gradle Wrapperを設定し、Wrapper一式をGit管理する。開発者個人のSDKパスを含む`local.properties`はGit管理しない。
- P0-05のGitHub ActionsはPython、uv、Node.js、JDKのversionをこの文書と同じ値で明示する。

## 5. repository共通規約

- text fileはUTF-8、原則LF、末尾改行ありとする。Windows batch fileだけCRLFとする。
- Pythonは4 spaces、TypeScript、JSON、YAML、Markdown、Kotlin、XML、propertiesは2 spacesとする。
- Markdownでは明示的な改行に使う行末spacesを許可する。それ以外のtext fileでは行末spacesを削除する。
- `.env`、秘密鍵、Android署名鍵、ローカルDB、収集データ、build成果物、IDE固有ファイルはGit管理しない。
- `.env.example`、lock file、Gradle Wrapperは再現可能な開発・CIに必要なためGit管理する。

規則の機械可読な定義は、repositoryルートの`.editorconfig`、`.gitattributes`、`.gitignore`を正とする。

## 6. 一次情報

- [Python release status](https://www.python.org/downloads/)
- [uv installation and version pinning](https://docs.astral.sh/uv/getting-started/installation/)
- [uv projects and lock file](https://docs.astral.sh/uv/concepts/projects/layout/)
- [Node.js releases](https://nodejs.org/en/about/previous-releases)
- [Android Gradle Plugin 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Android Java versions and Gradle JDK](https://developer.android.com/build/jdks)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html)

# 日程（Android V1 · 第 3 批）

第 1 批：新建事件 → 本地保存 → 日视图显示 → 重启后仍在。
第 2 批：`.ics` 导出 + 本地备份。
第 3 批：`.ics` 导入（解析 → 预览 → 去重 → 原子落库）。
月视图（用户主诉求）：默认打开就是月历网格，点某天进入日视图。
契约依据 PRD-02 v0.9（事件模型 / 时区 / RRULE / fingerprint v1）与 PRD-06 v0.8（最小布局规格）。

| 项 | 值 |
| --- | --- |
| 语言 | Kotlin 2.0.21 |
| 构建 | Gradle 8.9 + AGP 8.7.2 |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| 模块 | 单模块 `:app` |
| 本地库 | `SQLiteOpenHelper` + WAL，事件表 + 例外表 + 导入 staging 表 |
| UI | View 体系（AppCompat + Material 3），无 Compose |
| 测试 | JUnit + Robolectric 4.14.1（98 个用例） |

## 目录

```
app/src/main/java/com/zzq/calendar/
├── AppGraph.kt                 依赖装配（仓库 / 调度器，测试可替换）
├── model/
│   ├── Models.kt               EventMaster / EventException / EventInstance / InstanceKey
│   ├── TimeZones.kt            墙钟 ↔ 绝对时刻、TZID 解析、DST 行为
│   ├── RRule.kt                RRULE 解析与支持度标记
│   ├── RRuleExpander.kt        按窗口展开、EXDATE/RDATE、COUNT/UNTIL/WKST
│   ├── MonthGrid.kt            月历 6×7 网格（周一起始）
│   └── FingerprintV1.kt        fingerprint v1 规范化 + SHA-256
├── data/
│   ├── CalendarDbHelper.kt     schema v2（events / event_exceptions / import_staging）
│   ├── EventStore.kt           读写与映射（事务）、staging
│   ├── EventRepository.kt      仓库层、日视图聚合、校验
│   └── RRuleBuilder.kt         编辑器预设 → RRULE 原文
├── export/
│   ├── IcsExporter.kt          RFC 5545 导出
│   └── JsonBackup.kt           JSON 备份清单
├── importer/
│   ├── IcsParser.kt            编码识别、VEVENT / TZID / 例外 / VALARM 解析
│   └── IcsImporter.kt          预览、去重、批次规则、staging 单事务落库
└── ui/
    ├── MonthActivity.kt        月视图（默认入口，日期网格 + 事件圆点）
    ├── MainActivity.kt         日视图（列表 + 时间轴），可由月视图进入
    ├── DayTimelineView.kt      24h 时间轴自绘、重叠分列、点击区扩展
    ├── EventEditActivity.kt    新建 / 编辑（全天、时区、重复）
    └── UiFormat.kt             中文日期与时间格式
```

## 构建与测试

需要 JDK 17 与 Android SDK 35（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

Robolectric 首次运行会下载 `android-all-instrumented`。离线或受限环境可设置：

```bash
export ROBOLECTRIC_DEPS=/dir/with/android-all-instrumented-15-robolectric-12650502-i7.jar
export ROBOLECTRIC_HOME=/short/writable/dir
```

（`java.io.tmpdir` 会被指向 `ROBOLECTRIC_HOME/tmp`，避免 Windows 长路径导致 SQLite 打不开。）

## 数据模型（schema v2）

`events`（主事件）

| 字段 | 说明 |
| --- | --- |
| `id`, `uid` | 本地主键 / 事件 UID（实例键成员） |
| `calendar_id`, `source_type`, `source_id` | 归属与来源，本批固定 `cal-local` / `local` |
| `title`, `description`, `location`, `url` | 文本字段 |
| `all_day` | 0/1；全天事件墙钟固定为 00:00:00，`dtend_wall` 为**排他**结束日 |
| `dtstart_wall`, `dtend_wall` | `YYYY-MM-DDTHH:MM:SS`，无时区后缀 |
| `tzid` | IANA 时区；全天事件存展示时区 |
| `rrule_raw` | RRULE 原文，只解析不重写 |
| `exdate_list`, `rdate_list` | 换行分隔的墙钟值（规范化后排序） |
| `valarm_list` | 换行分隔 `trigger_type,offset,tzid` |
| `tombstone`, `rev`, `fingerprint` | 墓碑、版本、写入时计算的指纹 |
| `tzdata_version`, `created_at`, `updated_at` | 展开上下文与时间戳 |

`event_exceptions`（实例例外）

- 键：`UNIQUE(event_id, recurrence_id_wall, tzid)`，删除主事件级联删除。
- `status`：`modified` / `cancelled`；`cancelled` 不出现在视图。
- 覆盖字段：`title`, `all_day`, `dtstart_wall`, `dtend_wall`, `description`, `location`。
- 例外定位遵循 PRD：`(UID, RECURRENCE-ID 墙钟 + TZID)`；`tzdata_version` 不是键成员。

`import_staging`（导入暂存，schema v2 新增）

- 字段：`batch_id, seq, line, payload`；`payload` 是原始 `VEVENT` 块（含例外），可回溯。
- 只在用户确认后写入；正式落库在同一事务里写 `events` 并清空该批次。
- 进程被杀 / 崩溃时事务回滚，staging 残留下次启动即丢弃，不会进入正式库。

所有读写都在 `Dispatchers.IO` 上执行（`EventRepository`），主线程只做渲染，避免 ANR。

## fingerprint v1

`FingerprintV1.canonicalJson` 按 PRD-02 §3.3 第 4 条实现：

- 字段：`all_day, calendar_id, dtstart_wall, dtend_wall, tzid, exdate[], rdate[], rrule_raw, exceptions[], title, tombstone, valarm[]`
- 键名字典序、UTF-8、分隔符 `,`/`:`、无空白、`ensure_ascii=false`；集合先排序。
- SHA-256 小写十六进制。
- 3 组冻结向量逐字节一致，见 `app/src/test/java/com/zzq/calendar/model/FingerprintV1Test.kt`。

实现细节与一处 **PRD 文本与向量不一致** 见 `docs/contract-notes.md`（`valarm` 排序）。

## RRULE 支持矩阵

| 参数 | 状态 | 说明 |
| --- | --- | --- |
| `FREQ` DAILY / WEEKLY / MONTHLY / YEARLY | 支持 | |
| `INTERVAL` | 支持 | |
| `BYDAY`（含 `2TU` / `-1FR` 序数） | 支持 | |
| `BYMONTHDAY`（含负数） | 支持 | 月末不存在日期跳过 |
| `COUNT` / `UNTIL` | 支持 | 二者同时出现时以 `COUNT` 为准并记录 warning |
| `WKST` | 支持 | 影响 WEEKLY + INTERVAL > 1 的周界 |
| `EXDATE` | 支持 | 先按 COUNT 生成再剔除（RFC 语义） |
| `RDATE` | 只读 | 追加展示，不参与 COUNT |
| `BYSETPOS` / `BYMONTH` / 其他 | 只读 | 原文保留，编辑器锁定重复规则，禁止静默改写 |

YEARLY 的 `BYMONTHDAY` / `BYDAY` 在未给 `BYMONTH` 时按 DTSTART 所在月份解释（RFC 5545 §3.3.10）。
20 条基线用例见 `RRuleExpanderTest`（case01 ~ case20），另含 EXDATE、RDATE、无限规则窗口裁剪等 7 条补充用例。

## 时区与 DST

- 存墙钟 + TZID，展开时换算绝对时刻；显示按事件 TZID，不随设备时区漂移。
- 春季不存在的本地时刻：`LocalDateTime.atZone` 顺延到跳变后的第一个有效时刻。
- 秋季重复的本地时刻：取第一次（DST 侧）。
- 纽约每周一 09:00 跨 2027-03-14 / 2027-11-07 的用例见 `DstRuleTest`。

## 月视图（默认入口）

- 打开应用先看到月历：6 行 × 7 列，周一开头，显示相邻月补位日期（灰色）。
- 每格显示当天事件数：1–3 个圆点，超过 3 个显示 `●●●+`；今天加粗并高亮。
- 顶部可翻上 / 下月，底部「今天」回到当月；点某天进入该天的日视图。
- 日视图左上角「月视图」返回月历。
- 截图：`docs/screenshots/month-view.png`、`month-view-dark.png`、`month-view-200.png`。

## 日视图与无障碍

- 默认布局：顶部日期 / 时区 → 全天区 → 当天事件列表 → 24 小时时间轴。
- 时间轴 1 小时 = 48dp；事件条最小高度 20dp；事件条点击区扩展到 44dp（视觉不夸大时长）。
- 重叠事件按列并排；当前时间线仅在当天显示。
- 所有按钮 / 列表行 / 全天行最小 48dp；字号 200% 时列表行与按钮随内容增高，不截断。
- 深浅色配色对（正文、次要文字、主按钮、事件条、全天条、小时刻度）对比度均 ≥ 4.5:1，由 `AccessibilityTest` 断言。
- 200% 字号下时间轴刻度列宽按文字实际宽度自适应。

见 `docs/screenshots/day-view.png` 与 `docs/screenshots/day-view-200.png`。

## 导出与备份（第 2 批）

- `.ics` 导出：单条（编辑页「导出」）与全量（首页右上「更多 → 导出全部日程」）。标准 `VCALENDAR` / `VEVENT`，含 `UID`、`DTSTAMP`、带 `TZID` 的 `DTSTART` / `DTEND`、`RRULE` / `EXDATE` / `RDATE` 原文、`VALARM`。
- 全天事件写 `DTSTART;VALUE=DATE` / `DTEND;VALUE=DATE`，不带 `TZID`；`DTEND` 为排他结束日。
- 例外单独输出 `VEVENT`：同 `UID` + `RECURRENCE-ID`（原开始时间），`cancelled` 例外带 `STATUS:CANCELLED`。
- 文本转义遵循 RFC 5545（`\\` `\;` `\,` `\n`），UTF-8、CRLF、75 字节折行（按码点切分，不拆 UTF-8 字符）。
- JSON 备份：`{schema_version, exported_at, event_count, parts[]}`；每片 ≤ 5000 条或 ≤ 20MB，片内事件字段与 PRD-02 DTO 一致（另含 `title/description/location/url` 文本字段，避免备份丢内容）。
- 落盘走 SAF `ACTION_CREATE_DOCUMENT`，不申请存储权限。

## 导入（第 3 批）

- 入口：首页右上「更多 → 导入 .ics」。SAF `ACTION_OPEN_DOCUMENT`（SAF 打开文档），不申请存储权限。
- 编码：UTF-8（含 BOM）/ GBK（GB18030）/ UTF-16 BOM；都无法严格解码时整批拒绝（`E_ENCODING`），不写库。
- 解析：`VCALENDAR` / `VEVENT`、`TZID` / `VTIMEZONE`、`RRULE` / `EXDATE` / `RDATE` 原文、`RECURRENCE-ID` 例外（同 UID 归并到主事件）、`VALARM`（相对时长与绝对时间）。
- 时区：TZID 无法识别但有 `VTIMEZONE` 时，按 `TZOFFSETTO` 存为固定偏移（如 `+05:30`）并在预览提示；无 `VTIMEZONE` 时按 UTC，原串不改写。
- 预览：解析成功 / 失败条目（带行号）、重复（同 UID + 同指纹）、冲突（同 UID 内容不同，默认不覆盖）、目标日历；用户确认后才写库。
- 批次规则（PRD-03 v0.8）：0 失败全成功；`0 < failed ≤ 5%` 部分成功（失败项带行号）；`failed > 5%` 或编码 / 文件过大等致命错误整批回滚。
- 原子落库：先写 `import_staging`，再在单事务中迁移到 `events` / `event_exceptions` 并清空 staging；中途中断不会留下半批数据。
- 实测：1000 条 `.ics` 导入 338ms（JVM / Robolectric，参考值）。

## 第 3 批验收对照

| 验收项 | 状态 | 证据 |
| --- | --- | --- |
| 20 条基准导入字段与源文件一致（含重复、例外、VALARM） | 通过 | `IcsParserTest.twenty baseline events keep their fields`、`IcsImportTest.baseline import keeps fields and a re-import only skips` |
| 同一文件导入两次，第二次全部跳过 | 通过 | 同上：第二次 `created = 0`、`skipped = 20`，事件数不变 |
| 4% 失败部分成功；6% 失败零落库 | 通过 | `IcsImportTest.four percent failures import partially and keep line numbers`、`six percent failures roll the whole batch back`（另含 5% 边界用例） |
| 编码错误 / 超大文件 / 中途杀进程不产生半批 | 通过 | `encoding error and oversized files write nothing`、`staged batch survives nothing after restart cleanup`（事务回滚 + staging 丢弃） |
| 1000 条 ≤ 20s | 通过 | `one thousand events import within twenty seconds`，实测 338ms |
| `assembleDebug` 与既有 72 个单测全绿 | 通过 | 87 个测试全绿（含原有 72 个），`app-debug.apk` 构建成功 |
| 真机导入验证 | 未执行 | `adb devices` 列表为空（无已授权设备），需连接手机并允许 USB 调试后复测 |

## 第 2 批验收对照

| 验收项 | 状态 | 证据 |
| --- | --- | --- |
| 20 条基准事件导出后解析库读回一致 | 通过 | `IcsExporterTest.twenty baseline events round-trip through biweekly`（biweekly 0.6.8） |
| 全天事件无 TZID、日期不偏移 | 通过 | `IcsExporterTest.all-day events carry VALUE=DATE without TZID and do not shift` |
| RRULE + EXDATE + 例外的 RECURRENCE-ID 为原开始时间 | 通过 | `IcsExporterTest.recurring event with exdate and exceptions keeps RECURRENCE-ID at original start` |
| 1000 条导出事件数一致、分片符合 5000 / 20MB | 通过 | `JsonBackupTest`（1000 条单分片；5001 条两片；按字节分片） |
| 真机导出到 Downloads 回读 | 未执行 | adb 设备 `10.2.9.145:5555` 处于 `unauthorized`，需在手机上确认 USB 调试授权后复测 |
| `assembleDebug` 与既有单测全绿 | 通过 | 71 个测试全绿，`app-debug.apk` 构建成功 |

## 第 1 批验收对照

| 验收项 | 状态 | 证据 |
| --- | --- | --- |
| 3 组 fingerprint 向量一致 | 通过 | `FingerprintV1Test`（逐字节 + 哈希） |
| 20 条 RRULE 用例展开一致 | 通过 | `RRuleExpanderTest` case01–case20（期望值由 python-dateutil 交叉生成） |
| DST 春 / 秋不漂移 | 通过 | `DstRuleTest`（含绝对时刻断言） |
| 新建 → 杀进程 → 重开仍显示 | 通过（JVM/Robolectric 级） | `EventPersistenceTest` 关闭并重开数据库；`MainActivityTest` 重建 Activity |
| 点击区 ≥ 44dp；200% 不破 | 通过 | `DayTimelineViewTest`、`AccessibilityTest`、200% 截图 |
| 冷启动无崩溃；读写无 ANR | 部分验证 | 63 个测试全绿、Activity 启动无异常；真实设备冷启动 / ANR 需 QA 用 APK 复测 |

## 已知限制 / 待确认

1. 第 2、3 批导出 / 导入未做真机复测：`adb devices` 当前无已授权设备，需连接手机并允许 USB 调试；第 1 批真机回归记录见文末。
2. 性能按垂直切片处理：日视图加载全部主事件后在内存展开；未做大数据量索引与分页（PRD 5,000 事件指标待后续批次）。
3. 不分实例编辑 / 删除（「本次 / 本次及以后 / 全部」未实现），符合本批范围。
4. 复杂 RRULE（EXDATE / RDATE / BYSETPOS / INTERVAL>1 / 多 BYDAY / UNTIL）编辑时锁定重复段，只改标题与时间。
5. 全天事件指纹包含展示 TZID，跨设备展示时区不同会得到不同指纹，见契约问题 Q3。
6. 契约问题 Q1–Q4 见 `docs/contract-notes.md`；导入实现中的取舍见 `docs/import-notes.md`。
7. 导入的已知限制：`RECURRENCE-ID;RANGE=THISANDFUTURE` 按单次例外导入（未按 B 方案拆系列）；失败明细只在预览中展示，未提供导出文件；一个 `VTIMEZONE` 只取第一个 `TZOFFSETTO`。
8. 月视图只显示圆点与数量，不显示事件标题；周视图（7 天时间轴）和月汇总尚未做。

## 仓库

远端 `git@github.com:lwhlove1221/zzq-calendar-android.git`（私有），默认分支 `main`。本仓库只放 Android 客户端；PRD 与评估文档仍在 Multica issue 中维护。

## 真机回归（2026-11-23）

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi 24129PN74C（`dada`） |
| Android | 17（API 37） |
| ROM | HyperOS `OS4.0.4.0.XOCCNXM`（V816） |
| 安装包 | `app-debug.apk`，versionName 0.1.0 / versionCode 1 |

结果：

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 新建事件 → 杀进程 → 重开 | 通过 | 新建 `ZZQ37devicetest`、`ZZQ37second`，`am force-stop` 后冷启动仍在日视图 |
| 冷启动 3 次 | 通过 | `am start -W` TotalTime：211 / 198 / 207 ms（另测 229 / 219 / 205 / 212 ms） |
| 200% 字号 | 通过（ZZQ-38 修复后复测） | 列表/时间轴不截断、按钮可点；顶栏与编辑页标题不再压状态栏，日期按钮文字不再贴胶囊边 |
| 深色模式 | 通过 | 深色背景 + 浅色文字，对比度正常 |
| logcat 崩溃 / ANR | 通过 | 全流程后 `logcat -b crash` 为空，logcat 无 `FATAL` / `ANR`，dropbox 无本应用记录 |

已修复（ZZQ-38，2026-11-23）：两页根布局补 `fitsSystemWindows` 处理状态栏/导航栏 inset；开始/结束日期按钮增加上下内边距，200% 字号换行后文字不再贴住胶囊边框。100% / 200% 字号、浅色 / 深色已在真机复测。

# 验证记录

环境：JDK 17（Microsoft OpenJDK 17.0.21.1）、Gradle 8.9、Android SDK 35（build-tools 35.0.0）、Robolectric 4.14.1、Windows x64。

执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 结果

| 测试类 | 用例数 | 失败 |
| --- | ---: | ---: |
| `FingerprintV1Test` | 5 | 0 |
| `RRuleExpanderTest` | 27 | 0 |
| `DstRuleTest` | 5 | 0 |
| `EventPersistenceTest` | 8 | 0 |
| `DayTimelineViewTest` | 5 | 0 |
| `MainActivityTest` | 3 | 0 |
| `AccessibilityTest` | 4 | 0 |
| `UiFormatTest` | 4 | 0 |
| `ScreenshotTest` | 3 | 0 |
| `IcsExporterTest`（第 2 批） | 4 | 0 |
| `JsonBackupTest`（第 2 批） | 4 | 0 |
| **合计** | **72** | **0** |

构建产物：`app/build/outputs/apk/debug/app-debug.apk`（6.41 MB）。

## 关键用例

- 指纹：3 组冻结向量的规范化 JSON 与 SHA-256 均逐字节一致；集合乱序不改变结果；转义稳定。
- RRULE：case01–case20 覆盖 DAILY / WEEKLY / MONTHLY / YEARLY、INTERVAL、BYDAY（含序数）、BYMONTHDAY（含负数 / 月末跳过）、COUNT、UNTIL、WKST；补充 EXDATE、RDATE、BYSETPOS 只读、无限规则窗口裁剪。
- DST：纽约 2027-03-01 起每周一 09:00，跨春令时后由 14:00Z 变为 13:00Z；2027-10-25 起跨秋令时由 13:00Z 变为 14:00Z；02:30 不存在时刻顺延为 03:30；01:30 重复时刻取第一次（05:30Z）。
- 持久化：写入后关闭 `SQLiteOpenHelper`，重新打开仍能按日查询；重复事件按周展开；EXDATE 与 cancelled 例外不显示；全天事件进入全天桶；更新递增 `rev` 并重算指纹；删除后不再返回。
- 界面：Robolectric 重建 Activity 后事件仍显示；200% 字号下按钮 / 行高 ≥ 44dp；时间轴事件条 ≥ 20dp 且点击区 ≥ 44dp；重叠事件并排；深浅色 7 组配色对比度 ≥ 4.5:1。

## 视觉验证

`docs/screenshots/day-view.png`（100% 字号）与 `docs/screenshots/day-view-200.png`（200% 字号）由 `ScreenshotTest` 使用 Robolectric 原生图形渲染真实布局生成。

第 2 批导出入口：`app/build/screenshots/export-main.png`（首页「更多」）与 `export-edit.png`（编辑页「导出」），同样由 `ScreenshotTest` 渲染。

## 第 2 批：导出与备份

- `IcsExporterTest`：20 条基准事件经 biweekly 0.6.8 读回，标题 / 备注 / 地点 / URL / 起止墙钟 / 时区 / RRULE / EXDATE / RDATE / VALARM 逐项一致；全天事件 `DTSTART;VALUE=DATE` 无 `TZID` 且日期不偏移；RRULE + EXDATE + 修改/取消例外的 `RECURRENCE-ID` 保持原开始时间；输出为 CRLF、每行 ≤ 75 字节、长中文标题折行后读回不损坏。
- `JsonBackupTest`：1000 条事件 → `event_count=1000` 且单分片；5001 条 → 5000 + 1 两片；按字节上限分片时每片 `bytes` ≤ 上限且事件总数守恒；例外与 `valarm` 字段按 DTO 输出。
- 真机导出（SAF → Downloads）未执行：adb 设备 `10.2.9.145:5555` 为 `unauthorized`，需手机端确认调试授权。

## 未覆盖

- 真机 / 模拟器：当前执行环境无连接设备，未做真实进程 kill、冷启动 P95、后台 / 锁屏、厂商 ROM 差异与 ANR 观测。
- 性能：未做 1,000 / 5,000 事件滚动帧率与首屏 800ms 指标（不在本批范围）。

## 第 5 批：本地提醒与 VALARM 写回

- `ReminderPlannerTest`（11 条）：时长偏移按绝对时间轴计算；一个事件多提醒各生成一个调度键；全天事件按事件 TZID 的墙钟算「当天 09:00」；春令时 02:30 顺延为 03:30（07:30Z）；秋令时 01:30 取第一次（05:30Z）；春令时当天「提前 1 天」为 23 小时；重复事件按实例展开；cancelled 例外不排；镜像事件零提醒；过期提醒丢弃；调度键为 `(event_id, occurrence_key, offset, tzdata_version)`。
- 全量单元测试 143 条通过（原 132 + 新增 11），`assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`。
- 未执行：真机 20 条提醒误差 / 无重复响、改期删除后旧提醒不再响、通知与精确闹钟权限拒绝、系统日历写回后 VALARM 可见。本机当前无 adb 连接设备。

## 第 6 批：导航改版（ZZQ-56）

- 底部 Tab 顺序改为 今天 / 周 / 月 / 年，四个视图共用；「今天」进入当前日的日视图，周 / 月 / 年按当前锚点切换，日视图切换其它视图时保留正在看的日期。
- 公共 `SwipeNavigator`：四视图左滑下一页、右滑上一页；阈值 64dp、动画 160ms；支持范围 1900-01-01 ～ 2100-12-31，到边界不响应也不回弹。只在水平位移超过 touch slop 且大于垂直位移时接管手势，点击日期、纵向滚动不受影响。
- `SwipeGestureTest`（6 条）：方向、阈值、纵向忽略、动画时长 ≤200ms、范围边界；`SwipeNavigationTest`（6 条）：日 / 周 / 月 / 年滑动翻页、纵向不翻页、2100-12 不越界；`ViewSwitchTest` 新增 Tab 顺序、今天跳日视图、日视图 Tab 选中、日视图切换保持锚点。
- 离线执行 `:app:testDebugUnitTest :app:assembleDebug`：161 条测试全部通过，`app/build/outputs/apk/debug/app-debug.apk` 正常产出。
- 未执行：真机滑动手感与动画帧率；adb 仍被用户占用，等通知后补。

## 第 7 批：年 / 周视图信息密度（ZZQ-57）

- 年视图改为 12 个月卡片：每格只给当月概况（「N 条」/「无日程」），不再内联日期；当月卡片用描边 + 底色标出。根因：原 `TextPaint.apply {}` 里的 `density` 实际解析到 `TextPaint.density`（恒为 1.0），所有年视图文字只有标称字号的 1/3，改成 `dpScale` 后字号才生效。
- 周视图改为 7 条日期行：单屏 7 天全部可见，不横向滚动、不拖拽；每行左侧「周几 + 日期」（今天红圈），右侧按时间列出「HH:mm 标题」/「全天 标题」，超出上限折成「+N 条」；点击整行进日视图。200% 字号降到 1 行 + 计数，避免静默截断。
- 删除只被周视图使用的 `WeekTimelineView`（24 小时网格需纵向滚动，看不全当天日程）。
- 单测 172 条通过（新增：年视图月度计数、周视图 7 行一屏、超量折叠、200% 字号折叠、点击行进日视图）；`assembleDebug` 正常产出。
- 真机 OPPO PHN110（1116×2484 / 520dpi）实测：年、周各一张截图，见 ZZQ-57 评论附件。
- 未覆盖：真机深色模式目视（Robolectric 深色截图已能出图）；横屏（App 未适配横屏）。

## 第 8 批：滑动手感（ZZQ-59）

- `SwipePageLayout` 改为跟手：水平位移超过 touch slop 即接管手势，`page.translationX` 实时跟随手指；首 / 末页按 0.3 阻尼拉、松手回弹，单次拖动最多露出 0.6 屏。
- 松手判定 `SwipeGesture.releaseDirection`：横向甩动速度 ≥ 700dp/s 直接翻页，否则需拖过 0.18 屏宽（且不小于 64dp）；松手动画 260ms + `DecelerateInterpolator`，未过阈值原路回弹。
- 过渡：松手瞬间把离场页抓成半分辨率 Bitmap 覆盖层，随后离场页滑出（1.0→0.95 缩放 / 1.0→0.6 透明度），同时目标页从 0.45 屏处滑入并 0.95→1.0 / 0.6→1.0 恢复。抓图发生在重载之前，正文不会在手指下换字。
- 四视图共用同一 `SwipePageLayout`，锚点 / 边界仍走 `CalendarRange`，未动 Tab 顺序与月视图布局。
- `SwipeGestureTest`（8 条）：距离 / 甩动 / 纵向忽略 / 0.18 屏宽 / 260ms 落在 250–300ms；`SwipeNavigationTest`（7 条）新增「松手前页面跟手位移」；`ScreenshotTest` 新增 `renderSwipeFrames` 输出 `swipe-drag.png` / `swipe-enter.png` / `swipe-settled.png`。
- 单测 175 条全通过，`assembleDebug` 正常产出 `app/build/outputs/apk/debug/app-debug.apk`。
- 未执行：真机滑动与 `dumpsys gfxinfo` 掉帧率。OPPO（10.2.9.145）安装被系统「安全验证」滑块拦住，需手机上确认安装后补测。
## 第 9 批：设置页 + 主题换肤（ZZQ-58）

- 「设置」作为底部 Tab 第 5 项常驻，四个视图一步可达；设置页复用原日视图 ⋮ 的五个动作（同步系统日历 / 冲突记录 / 导入 .ics / 导出全部 .ics / 备份 JSON），逻辑与文件名规则未改，日视图 ⋮ 已删除。
- 主题：`ThemePrefs`（SharedPreferences）保存 明暗（跟随系统 / 浅色 / 深色）× 强调色（蓝 / 绿 / 紫）；`AppCompatDelegate` 负责明暗，`theme.applyStyle` 叠加强调色 overlay；切换即时生效，冷启动由 `CalendarApp.onCreate` 恢复。
- 颜色改为主题属性（`colorPrimary` / `colorOnPrimary` / `colorPrimaryContainer` / `colorOnPrimaryContainer`），日 / 周 / 月 / 年 / 设置与代码绘制的格子、事件条、圆点统一取色；今天红圈保持红色不变。
- `ThemePrefsTest`（4 条）、`SettingsActivityTest`（5 条）、`ThemeScreenshotTest`（3 条渲染 9 种组合 + 四视图浅深 + 设置页浅深）；`AccessibilityTest` 扩到 3 套强调色的前景 / 背景对比度 ≥ 4.5:1。
- 全量单测通过，`assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`。
- 未执行：OPPO 真机（`c7b32954`）在复测时离线（`adb devices` 无设备），深色下格子点 / 选中态 / 今天圈的肉眼可辨性只有 Robolectric 渲染与对比度断言，未做真机截图。

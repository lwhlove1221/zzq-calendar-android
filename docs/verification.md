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

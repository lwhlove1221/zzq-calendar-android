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
| `ScreenshotTest` | 2 | 0 |
| **合计** | **63** | **0** |

构建产物：`app/build/outputs/apk/debug/app-debug.apk`（6.41 MB）。

## 关键用例

- 指纹：3 组冻结向量的规范化 JSON 与 SHA-256 均逐字节一致；集合乱序不改变结果；转义稳定。
- RRULE：case01–case20 覆盖 DAILY / WEEKLY / MONTHLY / YEARLY、INTERVAL、BYDAY（含序数）、BYMONTHDAY（含负数 / 月末跳过）、COUNT、UNTIL、WKST；补充 EXDATE、RDATE、BYSETPOS 只读、无限规则窗口裁剪。
- DST：纽约 2027-03-01 起每周一 09:00，跨春令时后由 14:00Z 变为 13:00Z；2027-10-25 起跨秋令时由 13:00Z 变为 14:00Z；02:30 不存在时刻顺延为 03:30；01:30 重复时刻取第一次（05:30Z）。
- 持久化：写入后关闭 `SQLiteOpenHelper`，重新打开仍能按日查询；重复事件按周展开；EXDATE 与 cancelled 例外不显示；全天事件进入全天桶；更新递增 `rev` 并重算指纹；删除后不再返回。
- 界面：Robolectric 重建 Activity 后事件仍显示；200% 字号下按钮 / 行高 ≥ 44dp；时间轴事件条 ≥ 20dp 且点击区 ≥ 44dp；重叠事件并排；深浅色 7 组配色对比度 ≥ 4.5:1。

## 视觉验证

`docs/screenshots/day-view.png`（100% 字号）与 `docs/screenshots/day-view-200.png`（200% 字号）由 `ScreenshotTest` 使用 Robolectric 原生图形渲染真实布局生成。

## 未覆盖

- 真机 / 模拟器：当前执行环境无连接设备，未做真实进程 kill、冷启动 P95、后台 / 锁屏、厂商 ROM 差异与 ANR 观测。
- 性能：未做 1,000 / 5,000 事件滚动帧率与首屏 800ms 指标（不在本批范围）。

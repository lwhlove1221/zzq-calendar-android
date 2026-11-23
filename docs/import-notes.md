# 第 3 批 `.ics` 导入实现说明

契约依据 PRD-03 v0.8（导入批次口径）与 PRD-02 v0.9（事件模型 / 指纹 / RRULE）。本批未改导出、事件模型、指纹算法与 RRULE 语义。

## 处理链路

`IcsParser`（字节 → `ParsedEvent`）→ `IcsImporter.preview`（去重 / 冲突 / 批次判定）→ 用户确认 → `IcsImporter.import`（staging → 单事务落库）。

## 关键取舍

| 项 | 实现 | 原因 |
| --- | --- | --- |
| 编码识别 | BOM → 严格 UTF-8 → 严格 GB18030 | 严格解码失败才算编码错误；GBK 文件不会静默变成乱码 |
| 去重 | 有 UID：同 UID 同指纹跳过、同 UID 不同指纹冲突（不覆盖）；无 UID：仅指纹 | PRD-03 §1.1、§3.3.1 |
| 批次判定 | `failed * 100 > total * 5` 整批回滚，等于 5% 仍部分成功 | v0.8 冻结口径「> 5%」 |
| 原子落库 | staging 存原始 `VEVENT` 块，确认后单事务迁移并清空 | 中断只会留下 staging，下次启动丢弃 |
| TZID 未知 | 有 `VTIMEZONE` 取 `TZOFFSETTO` 存为固定偏移；否则 UTC；both 都在预览提示 | PRD-03 §4「按 UTC 偏移展示，不擅自改写原串」 |
| 例外 | 同 UID 的 `RECURRENCE-ID` 块归并为主事件的 `event_exceptions` | 与 schema v1 例外表一致 |

## 未做 / 已知限制

1. `RECURRENCE-ID;RANGE=THISANDFUTURE` 只按单次例外导入，未按 PRD-02 §3.3 第 3 条的 B 方案拆成两条系列；预览会提示。
2. 失败明细（行号 + 原因）只在预览弹窗展示，未提供单独导出文件。
3. 一个 `VTIMEZONE` 只取第一个 `TZOFFSETTO`，跨 DST 的自定义时区会按其中一个偏移展示。
4. `RDATE` 只读保留（与 PRD 一致），不参与编辑。

## 测试与实测

- `IcsParserTest`：20 条基准字段、例外归并、GBK、编码错误、缺失 VTIMEZONE、坏事件行号、无 UID 稳定性。
- `IcsImportTest`：二次导入全跳过、4% / 5% / 6% 批次判定、同 UID 冲突不覆盖、编码 / 超大文件零落库、staging 丢弃、1000 条性能。
- 1000 条导入实测 338ms（JVM / Robolectric；PRD 参考值 ≤ 20s）。

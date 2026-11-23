# 本批实现中的契约问题

以下 4 项在实现 PRD-02 v0.9 / PRD-06 v0.8 时发现，均**未改 PRD**；当前实现选择了可复现冻结向量的方案，需产品 / 研发总监确认后再进入导入、同步、iOS 批次。

## Q1（需修正 PRD 文本）VALARM 排序与冻结向量矛盾

- PRD-02 §3.3 第 4 条写：`valarm` 按 `(trigger_type, offset, tzid)` 排序。
- 测试向量 3 的实际期望哈希 `bcd5e845…203b39` 只有在按 `(offset, trigger_type, tzid)` 排序（duration -600 在 absolute 0 之前）时才能复现。
- 按 `(trigger_type, …)` 排序会得到 `5c23bff5…5a1216`，与冻结值不一致。
- 当前实现：**按向量排序**（offset 优先），`FingerprintV1Test` 逐字节通过。
- 建议：把 PRD §3.3 的排序口径改成 `(offset, trigger_type, tzid)`，或重新发布向量；否则云端 / iOS 对齐时必然分叉。

## Q2（覆盖缺口）实例例外的指纹字段

- 冻结向量 3 的例外对象只有 4 个键：`recurrence_id_wall, status, title, tzid`。
- 但「本次修改」需要覆盖起止时间 / 全天标志；这些字段若进入指纹会破坏向量 3 的哈希，若不进入则时间修改无法被去重 / 迁移校验发现。
- 本批：严格按 4 键生成指纹；编辑只针对主事件，不产生「修改时间」的例外，因此暂不触发缺口。
- 建议：在 V1.1 引入 `fingerprint v1.1` 或在 PRD 明确例外时间变更不在指纹覆盖范围。

## Q3（全天事件）TZID 是否应进入指纹

- PRD 要求全天事件内部存 `DATE` + 展示用日历时区，导出禁止带 TZID。
- 当前实现存展示 TZID 并把它纳入指纹；同一全天事件在上海与纽约创建会得到不同指纹，影响去重与迁移校验。
- 建议：全天事件的指纹把 `tzid` 规范化为空串（或固定 `"floating"`），并补一组全天向量。

## Q4（导入语义）COUNT 与 UNTIL 同时出现

- PRD-02 §3.2 规定二者互斥，但未定义同时出现时的行为。
- 当前实现取 `COUNT` 并记录 warning，展开不回退。
- 建议：导入预览直接拒绝该 RRULE（或标只读），而不是静默择一。

## 附：实现中的其他解释

- `YEARLY` + `BYMONTHDAY` / `BYDAY` 且无 `BYMONTH`：按 DTSTART 所在月份解释（RFC 5545）。python-dateutil 4.0 对该组合会跨月展开，与本实现不同；本实现以 RFC 为准。
- `UNTIL`：带 `Z` 按 UTC 绝对时刻比较，`DATE` 形式按日期包含比较，浮动形式按墙钟比较。
- `EXDATE` 不参与 COUNT 计数（RFC 语义）；`RDATE` 不参与 COUNT。

# 实验模型 stem 顺序实证（NCC）

> 2026-10-20 ｜ 设备 BF00A（骁龙 G100）｜ 工具 `StemOrderProbe`

## 为什么需要验证

UVR/MDX 运行时是**残差法**：模型只预测**一个** stem，另一个 = 源 − 预测。
而 `SherpaSourceSeparation` 的输出**恒为** `stem[0]=vocals` / `stem[1]=accompaniment`。

对 **Inst 系**（instrumental 导向）模型，它预测的其实是**伴奏** →
`stem[0]` 里装的其实是伴奏，**两轨完全对调**。

App 靠 `ModelSpec.stemOrder` 纠正（`ACCOMP_FIRST` = 落盘前交换）。

## 方法

用 **Spleeter 2-stem** 作参考（其顺序已知正确），对同一段 20s 音频分别分离，比较：

```
nccSame = corr(probe.stem0, ref.vocals)      // 高 → probe.stem0 是人声 → 顺序正确
nccSwap = corr(probe.stem0, ref.accomp)      // 高 → probe.stem0 是伴奏 → 需交换
```

判据：`|nccSwap − nccSame| >= 0.10` 才采信；否则标"证据不足"并保留原标注。

复现：
```bash
adb shell am start-service -n pub.miku.neuralstem/.devtools.TestCommandService \
  --es cmd ncccheck --es a0 /data/local/tmp/katyusha.mp3
adb logcat -s StemOrderProbe:I
```

## 结果（13 个实验模型，全部跑完）

| 模型 | same | swap | margin | 判定 | 原名猜的 |
|---|---|---|---|---|---|
| Inst_1 | 0.058 | 0.498 | 0.440 | ACCOMP_FIRST | ✅ |
| Inst_2 | 0.058 | 0.499 | 0.440 | ACCOMP_FIRST | ✅ |
| Inst_3 | 0.059 | 0.498 | 0.438 | ACCOMP_FIRST | ✅ |
| Inst_HQ_1 | 0.059 | 0.499 | 0.440 | ACCOMP_FIRST | ✅ |
| Inst_HQ_2 | 0.058 | 0.499 | 0.441 | ACCOMP_FIRST | ✅ |
| Inst_HQ_4 | 0.060 | 0.497 | 0.438 | ACCOMP_FIRST | ✅ |
| Inst_HQ_5 | 0.057 | 0.500 | 0.442 | ACCOMP_FIRST | ✅ |
| Inst_Main | 0.056 | 0.500 | 0.444 | ACCOMP_FIRST | ✅ |
| **KARA_2** | 0.059 | **0.498** | 0.439 | **ACCOMP_FIRST** | ❌ **猜错** |
| 1_9703 | **0.431** | 0.019 | 0.412 | VOCALS_FIRST | ✅ |
| 2_9682 | **0.431** | 0.019 | 0.412 | VOCALS_FIRST | ✅ |
| 3_9662 | **0.431** | 0.019 | 0.412 | VOCALS_FIRST | ✅ |
| Crowd_HQ_1 | 0.302 | 0.398 | **0.096** | ⚠️ **UNCERTAIN** | — |

### 判据有效性

结果高度自洽，说明判据没坏：

- 9 个 ACCOMP_FIRST 的 `same` 全部落在 **0.056–0.060**（方差极小）
- 3 个 VOCALS_FIRST 的 `same` 全部是 **0.431**（三者数值完全相同 —— 它们本就是同架构）
- 两组的 `swap` 也是 0.019 vs 0.498 的两极分布

## 两个发现

### 1. KARA_2 的名字骗了人

原代码按"KARA = 伴奏/卡拉OK 导向，但顺序正常"标 `VOCALS_FIRST`。
实测 `same=0.059 / swap=0.498`，与 Inst 系**完全一致**。

> 如果没做这个实证就直接放进模型库，选 KARA_2 的用户会听到
> **人声/伴奏对调** —— 而且很难判断是 bug 还是"这模型就这样"。

### 2. Crowd_HQ_1 无法判定

`same=0.302 / swap=0.398`，差值 0.096 < 0.10。

这是个**合唱/人群**模型：它既要保留人声又要保留部分伴奏，输出既不像纯人声
也不像纯伴奏（两个方向的相关都中等）。**NCC 判据对这种模型天然失效。**

**结论：不放进面向用户的模型库。** 保留在 `EXPERIMENTAL` 里供热切换速/研究。

## 局限（如实记录）

1. **只验证相对顺序，不验证音质。** NCC 高只说明"这轨像人声"，不说明"分离得好"。
2. **单曲单设备。** 用了一首歌（katyusha, 20s）+ BF00A。理论上是架构性问题与内容无关，
   但严格来说只证明了这一个组合。
3. **Crowd 类模型判据失效**（见上），需要人耳主观判断 —— 本轮没做。
4. **参考模型本身是 Spleeter**（非 UVR）。若 Spleeter 顺序有误，结论会整体反转；
   但它已被阶段 M 的多模型交叉验证过，可信。

## 对模型库的影响

| 模型 | 可否进模型库 | 理由 |
|---|---|---|
| 9 个 ACCOMP_FIRST（Inst 系 + KARA_2） | ✅ 可以 | margin 0.44，证据充分 |
| 3 个 28MB 通用（9703/9682/9662） | ✅ 可以 | margin 0.41，证据充分 |
| Crowd_HQ_1 | ❌ 不行 | 证据不足 + 模型定位特殊 |

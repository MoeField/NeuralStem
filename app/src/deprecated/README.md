# deprecated/ — 已废弃代码存档

本目录**不在任何 Android 源集内**（`app/src/deprecated/java` 不是 AGP 认识的源集路径），
仅作历史存档，不参与编译、不进 APK。IDE 中可直接查看。

## 为什么存档而不是删除（用户决定,2026-02）

这些代码是「Spleeter→TFLite」回退路线的实现。选型演进链：
1. Task 0a 初版计划：Spleeter → TFLite + Flex Delegate
2. 调研发现全网无现成 `.tflite` 产物，本地转换链风险高 → 改走 ONNX/sherpa-onnx
   （详见 `doc/research_separation.md` §5 选型变更）
3. Task 0d–0g 实测 sherpa 路线全胜（18–25× 实时、内存可控）
4. TFLite 的 3 个 so（`libtensorflowlite_jni.so` 等）ELF 加载段仅 4KB 对齐，
   在 Android 15+（16KB 页设备）无法加载；sherpa 全家 + 本项目胶水均为 16KB 对齐

→ TFLite 回退路线的两个前提（更易集成/更小体积）均不成立，且存在新设备兼容性风险，
故整体废弃。若未来需要重启该路线：
- 代码：本目录下 `SpleeterSeparator.kt`（TFLite Interpreter 封装，含 dynamic shape
  处理与allocateTensors 后查实际输出 shape 的经验）与 `Chunker.kt`（分块+交叉淡化
  权重数学，其思想已被 StreamingSeparator 继承并改进——权重归一化版）
- 依赖：`org.tensorflow:tensorflow-lite:2.14.0` + `tensorflow-lite-select-tf-ops`
  （build.gradle.kts 已删，恢复时加回，并需换 16KB 对齐的构建版本）
- 模型：TFLite 路线需要本地跑 TF1 转换链产出 `.tflite`（当初弃坑的原因，见 0a）
- 论证与实测数据：`doc/research_separation.md`、`doc/.memory.md` 附录一~七、
  `doc/.critical_steps.md` 第 8 条

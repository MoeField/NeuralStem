package pub.miku.neuralstem.player

import java.nio.ByteBuffer

/**
 * 渲染线程上的「变速/变调阶段」抽象（v0.4.0）。
 *
 * 当前两个实现：
 * - [SonicStage]：media3 Sonic（PSOLA + 线性插值），默认、MIT；
 * - [RubberBandStage]：Rubber Band R3（相位声码器），GPL 可选构建。
 *
 * 约定与 [SonicStage] 一致：
 * - 只允许渲染线程调用；
 * - [process]/[finish] 的 sink 返回实际写入字节数，<=0 视为失败；
 * - [positionFrames] 报告**媒体帧**（不是写出帧）。
 */
interface RenderStage {
    /** 是否需要经过本阶段；false 时调用方应走旁路。 */
    val isActive: Boolean

    /** 曲尾是否已排空。 */
    val isEnded: Boolean

    /** 本次 flush 以来写出的字节数（诊断）。 */
    val writtenBytes: Long

    fun setParams(speed: Float, pitchRate: Float)

    /** 应用待生效参数；返回修正后的源读头。 */
    fun applyParams(srcFrame: Long): Long

    fun process(input: FloatArray, frames: Int, sink: (ByteBuffer, Int) -> Int): Long

    fun finish(sink: (ByteBuffer, Int) -> Int)

    fun flushAt(sourceFrame: Long)

    fun positionFrames(): Long
}

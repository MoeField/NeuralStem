package pub.miku.neuralstem.spike

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Spleeter TFLite 推理封装(Task 0b Spike)。
 *
 * 模型契约(见 doc/research_separation.md §2 路线 A):
 * - 输入:44100Hz 立体声交织 float 波形,dynamic shape(shape_signature 带 -1)
 * - 输出:stems 波形(2-stem: vocals, accompaniment),STFT/iSTFT/mask 全在图内
 * - 必须 TFLite + Flex Delegate(tensorflow-lite-select-tf-ops)
 *
 * 用法:
 * ```
 * val sep = SpleeterSeparator(context, modelAssetPath = "models/spleeter_2stems.tflite")
 * sep.load()                     // 计时点 1: 模型加载
 * val (vocals, accomp) = sep.separate(chunk, onProgress = {...})
 * sep.close()
 * ```
 */
class SpleeterSeparator(
    context: Context,
    /** assets 内模型路径,或绝对路径(file:) */
    private val modelAssetPath: String,
    /** 推理线程数。动态 shape 无法用 GPU/NNAPI delegate,只能 CPU 多线程 */
    private val numThreads: Int = DEFAULT_THREADS,
) {
    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var modelBuffer: MappedByteBuffer? = null

    var loadTimeMs: Long = 0; private set

    /** 加载模型并分配张量。必须先于 separate() 调用。 */
    fun load() {
        val t0 = System.currentTimeMillis()
        val buffer = loadModelBuffer()
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
            // 注:不设置 NNAPI/GPU delegate —— 动态 shape 下会降级失败(调研报告 R1)
        }
        interpreter = Interpreter(buffer, options)
        modelBuffer = buffer
        loadTimeMs = System.currentTimeMillis() - t0
    }

    /**
     * 对一块音频执行分离。
     *
     * @param chunkInput 交织立体声 float PCM(来自 Chunker.Chunk.samples)
     * @param outputStemCount 模型 stem 数(2-stem 模型 = 2)
     * @return 每条 stem 的交织波形,顺序与模型输出张量一致
     */
    fun separate(chunkInput: FloatArray, outputStemCount: Int = 2): List<FloatArray> {
        val itp = interpreter ?: error("call load() first")
        val input = ByteBuffer.allocateDirect(chunkInput.size * 4)
            .order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(chunkInput)

        // 动态 shape:先 resize 到当前块的实际样本数(shape_signature 存在时安全)
        val inShape = itp.getInputTensor(0).shapeSignature()
        if (inShape != null && inShape.any { it == -1 }) {
            val shape = inShape.copyOf().also { it[0] = 1; it[1] = chunkInput.size }
            itp.resizeInput(0, shape)
            itp.allocateTensors()
        }

        // allocateTensors() 后才能拿到确定的输出 shape(可能与输入不等长,STFT 帧效应)
        val outputBuffers = Array(outputStemCount) { i ->
            val outShape = itp.getOutputTensor(i).shape()
            val elements = outShape.fold(1) { acc, d -> acc * maxOf(d, 1) }
            ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())
        }

        itp.run(input, outputBuffers)

        val outputs = Array(outputStemCount) { FloatArray(0) }
        for (i in 0 until outputStemCount) {
            outputBuffers[i].rewind()
            val fb = outputBuffers[i].asFloatBuffer()
            val arr = FloatArray(fb.remaining())
            fb.get(arr)
            outputs[i] = arr
        }
        return outputs.toList()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        modelBuffer = null
    }

    private fun loadModelBuffer(): MappedByteBuffer {
        if (modelAssetPath.startsWith("/")) {
            val f = File(modelAssetPath)
            require(f.exists()) { "Model file not found: $modelAssetPath" }
            return f.inputStream().channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        }
        appContext.assets.openFd(modelAssetPath).use { fd ->
            fd.createInputStream().channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    companion object {
        /** 4 线程:中端机 4 大核场景的保守起点,Spike 时可调参对比 */
        const val DEFAULT_THREADS = 4
    }
}

package com.bxane.nuvx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.nio.FloatBuffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnnxRuntimeModelTest {
    @Test
    fun beatModelExecutesInference() = runModel("beat_this_final0_q8.onnx", longArrayOf(1, 64, 128), 64 * 128, 2)

    @Test
    fun vocalModelExecutesInference() = runModel("vocals_umxhq_int8.onnx", longArrayOf(1, 2, 2049, 960), 2 * 2049 * 960, 1)

    private fun runModel(asset: String, shape: LongArray, elements: Int, outputsExpected: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.cacheDir, asset).also { file ->
            context.assets.open(asset).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        }
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(model.absolutePath, options).use { session ->
                val values = ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
                OnnxTensor.createTensor(environment, values, shape).use { input ->
                    session.run(mapOf(session.inputNames.first() to input)).use { outputs ->
                        assertEquals(outputsExpected, outputs.size())
                    }
                }
            }
        }
        model.delete()
    }
}

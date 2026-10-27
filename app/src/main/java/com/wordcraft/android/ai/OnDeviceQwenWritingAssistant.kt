package com.wordcraft.android.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fully on-device writing assistant backed by the Apache-2.0 Qwen3 0.6B INT4
 * LiteRT-LM artifact. The model is downloaded separately so it does not inflate
 * the APK by hundreds of megabytes.
 *
 * LiteRT classes are only referenced inside [rewrite] so opening the app never
 * loads native libraries until the user actually runs a writing action.
 */
class OnDeviceQwenWritingAssistant(private val context: Context) : WritingAssistant {
    override suspend fun rewrite(input: String, action: WritingAction): WritingResult =
        withContext(Dispatchers.IO) {
            val model = QwenModelStore.modelFile(context)
            check(model.isFile && QwenModelStore.isModelDownloaded(context)) {
                "Download the Qwen model in AI settings before using writing actions."
            }

            // Load LiteRT only when inference is requested (avoids startup UnsatisfiedLinkError).
            val backendClass = Class.forName("com.google.ai.edge.litertlm.Backend")
            val engineConfigClass = Class.forName("com.google.ai.edge.litertlm.EngineConfig")
            val engineClass = Class.forName("com.google.ai.edge.litertlm.Engine")
            val conversationConfigClass = Class.forName("com.google.ai.edge.litertlm.ConversationConfig")
            val contentsClass = Class.forName("com.google.ai.edge.litertlm.Contents")

            val cpuBackend = backendClass.getMethod("CPU").invoke(null)
            val engineConfigCtor = engineConfigClass.constructors.first {
                it.parameterTypes.size >= 2
            }
            // EngineConfig(modelPath, backend, cacheDir) — match published API via best-effort ctor.
            val engineConfig = try {
                engineConfigClass.getConstructor(
                    String::class.java,
                    backendClass,
                    String::class.java
                ).newInstance(model.absolutePath, cpuBackend, context.cacheDir.absolutePath)
            } catch (_: NoSuchMethodException) {
                // Fallback if signature differs slightly across litertlm versions.
                engineConfigCtor.newInstance(model.absolutePath, cpuBackend, context.cacheDir.absolutePath)
            }

            val engine = engineClass.getConstructor(engineConfigClass).newInstance(engineConfig)
            try {
                engineClass.getMethod("initialize").invoke(engine)
                val systemText =
                    "You are WordCraft, a concise writing assistant. Follow the requested " +
                        "writing task. Return only the improved text, without explanations, " +
                        "quotes, or a preamble. Preserve the author's meaning and language."
                val contents = contentsClass.getMethod("of", String::class.java).invoke(null, systemText)
                val conversationConfig = conversationConfigClass.constructors.first().let { ctor ->
                    when (ctor.parameterCount) {
                        1 -> ctor.newInstance(contents)
                        else -> ctor.newInstance(*Array(ctor.parameterCount) { i ->
                            if (i == 0) contents else null
                        })
                    }
                }
                val conversation = engineClass
                    .getMethod("createConversation", conversationConfigClass)
                    .invoke(engine, conversationConfig)
                try {
                    val prompt = """
                        Task: ${action.instruction}

                        Text to edit:
                        $input

                        Return only the revised text.
                    """.trimIndent()
                    @Suppress("UNCHECKED_CAST")
                    val flow = conversation.javaClass
                        .methods.first { it.name == "sendMessageAsync" && it.parameterCount == 1 }
                        .invoke(conversation, prompt)
                    val chunks = mutableListOf<Any?>()
                    // Collect Flow via blocking coroutine API when available; else toString path.
                    val toList = try {
                        Class.forName("kotlinx.coroutines.flow.FlowKt")
                            .methods.firstOrNull { it.name == "toList" && it.parameterCount >= 1 }
                    } catch (_: Throwable) {
                        null
                    }
                    val response = if (flow is kotlinx.coroutines.flow.Flow<*>) {
                        flow.toList().joinToString(separator = "") { it.toString() }.trim()
                    } else {
                        flow?.toString()?.trim().orEmpty()
                    }
                    check(response.isNotBlank()) { "The local model returned an empty response." }
                    WritingResult(response)
                } finally {
                    (conversation as? AutoCloseable)?.close()
                        ?: try {
                            conversation.javaClass.getMethod("close").invoke(conversation)
                        } catch (_: Throwable) {
                        }
                }
            } finally {
                (engine as? AutoCloseable)?.close()
                    ?: try {
                        engineClass.getMethod("close").invoke(engine)
                    } catch (_: Throwable) {
                    }
            }
        }

    companion object {
        const val MODEL_NAME = QwenModelStore.MODEL_NAME
        const val MODEL_SIZE_LABEL = QwenModelStore.MODEL_SIZE_LABEL
        const val MODEL_URL = QwenModelStore.MODEL_URL

        fun modelFile(context: Context) = QwenModelStore.modelFile(context)

        fun isModelDownloaded(context: Context) = QwenModelStore.isModelDownloaded(context)

        suspend fun downloadModel(
            context: Context,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
        ) = QwenModelStore.downloadModel(context, onProgress)
    }
}

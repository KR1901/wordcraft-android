package com.wordcraft.android.ai

/**
 * The downloadable on-device models. Every entry was checked against the Hugging Face file listing
 * (exact file name and size) **and** fetched with a plain unauthenticated HTTP request, so nothing
 * here can 404 or 401 because of a guessed path or a gated repository.
 *
 * Only the Qwen3.5 family is listed. The Qwen3 and Qwen2.5 builds were removed: they were the ones
 * that echoed the input back, summarised "fix grammar" into a third of its length, and (Qwen3)
 * leaked reasoning text into the answer. Qwen3.5 runs with thinking explicitly disabled in
 * [LiteRtWritingAssistant], and the echo/summary guards apply to every model regardless.
 *
 * Gemma models are deliberately absent: their repositories are gated, so a plain download returns
 * HTTP 401 unless the user signs in and accepts the licence.
 */
enum class LocalModel(
    val id: String,
    val label: String,
    val tagline: String,
    val fileName: String,
    val repo: String,
    val sizeLabel: String,
    val approxBytes: Long
) {
    QWEN35_08B(
        id = "qwen3.5-0.8b",
        label = "Qwen3.5 0.8B",
        tagline = "Recommended. Fast, and holds a long paragraph together.",
        fileName = "Qwen3.5-0.8B_int8.litertlm",
        repo = "litert-community/Qwen3.5-0.8B",
        sizeLabel = "965 MB",
        approxBytes = 964_566_992L
    ),
    QWEN35_2B(
        id = "qwen3.5-2b",
        label = "Qwen3.5 2B",
        tagline = "Best quality. ~2 GB download, needs ~2.5 GB free RAM.",
        fileName = "Qwen3.5-2B_int8.litertlm",
        repo = "litert-community/Qwen3.5-2B",
        sizeLabel = "2.0 GB",
        approxBytes = 2_119_744_416L
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"

    /** A download is complete when it is within 5% of the size the model card lists. */
    val minimumBytes: Long get() = (approxBytes * 0.95).toLong()

    companion object {
        fun fromId(id: String?): LocalModel = entries.firstOrNull { it.id == id } ?: QWEN35_08B
    }
}

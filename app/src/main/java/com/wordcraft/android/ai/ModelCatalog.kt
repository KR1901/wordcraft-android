package com.wordcraft.android.ai

/**
 * The downloadable on-device models. Every entry was checked against the Hugging Face file listing
 * (exact file name and size), so a download cannot 404 because of a guessed path.
 *
 * Trade-off is always quality against download size and tokens/second on a phone CPU.
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
    QWEN3_06B(
        id = "qwen3-0.6b",
        label = "Qwen3 0.6B",
        tagline = "Fastest. Good for small fixes, weakest at tone changes.",
        fileName = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        repo = "litert-community/Qwen3-0.6B-int4",
        sizeLabel = "347 MB",
        approxBytes = 347_000_000L
    ),
    GEMMA3_1B(
        id = "gemma3-1b",
        label = "Gemma 3 1B",
        tagline = "Best balance: follows instructions noticeably better than 0.6B.",
        fileName = "gemma3-1b-it-int4.litertlm",
        repo = "litert-community/Gemma3-1B-IT",
        sizeLabel = "584 MB",
        approxBytes = 584_000_000L
    ),
    QWEN3_17B(
        id = "qwen3-1.7b",
        label = "Qwen3 1.7B",
        tagline = "Strongest rewrites. Slowest on CPU, needs a free ~1 GB.",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        repo = "litert-community/Qwen3-1.7B",
        sizeLabel = "977 MB",
        approxBytes = 977_000_000L
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"

    /** A download is complete when it is within 5% of the size the model card lists. */
    val minimumBytes: Long get() = (approxBytes * 0.95).toLong()

    companion object {
        fun fromId(id: String?): LocalModel = entries.firstOrNull { it.id == id } ?: QWEN3_06B
    }
}

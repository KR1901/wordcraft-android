package com.wordcraft.android.ai

/**
 * The downloadable on-device models. Every entry was checked against the Hugging Face file listing
 * (exact file name and size) **and** fetched with a plain HTTP request, so nothing in this list can
 * 404 or 401 because of a guessed path or a gated repository.
 *
 * Gemma models are deliberately absent: their Hugging Face repositories are gated, so a plain
 * download returns HTTP 401 unless the user is signed in and has accepted the licence. Shipping one
 * produced exactly that failure in the field.
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
        tagline = "Fastest. Fine for small corrections, weakest at tone changes.",
        fileName = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        repo = "litert-community/Qwen3-0.6B-int4",
        sizeLabel = "347 MB",
        approxBytes = 347_000_000L
    ),
    QWEN3_17B(
        id = "qwen3-1.7b",
        label = "Qwen3 1.7B",
        tagline = "Good tone control. Slowest of the small set on a phone CPU.",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        repo = "litert-community/Qwen3-1.7B",
        sizeLabel = "977 MB",
        approxBytes = 977_000_000L
    ),
    QWEN25_15B(
        id = "qwen2.5-1.5b",
        label = "Qwen2.5 1.5B",
        tagline = "Strong instruction following. Large download, needs ~2 GB free RAM.",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        repo = "litert-community/Qwen2.5-1.5B-Instruct",
        sizeLabel = "1.6 GB",
        approxBytes = 1_598_000_000L
    ),
    QWEN35_2B(
        id = "qwen3.5-2b",
        label = "Qwen3.5 2B",
        tagline = "Newest architecture, text-only. ~2 GB download, needs ~2.5 GB free RAM.",
        fileName = "Qwen3.5-2B_int8.litertlm",
        repo = "litert-community/Qwen3.5-2B",
        sizeLabel = "2.0 GB",
        approxBytes = 2_119_744_416L
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"

    /** A download is complete when it is within 5% of the size the model card lists. */
    val minimumBytes: Long get() = (approxBytes * 0.95).toLong()

    companion object {
        fun fromId(id: String?): LocalModel = entries.firstOrNull { it.id == id } ?: QWEN3_06B
    }
}

package com.leaflite.viewer.data

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.leaflite.viewer.R

/** The five formats Leaf Lite can open. */
enum class DocType(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    @ColorRes val colorRes: Int
) {
    PDF("pdf", R.string.type_pdf, R.drawable.ic_type_pdf, R.color.type_pdf),
    DOCX("docx", R.string.type_docx, R.drawable.ic_type_doc, R.color.type_docx),
    XLSX("xlsx", R.string.type_xlsx, R.drawable.ic_type_sheet, R.color.type_xlsx),
    TXT("txt", R.string.type_txt, R.drawable.ic_type_text, R.color.type_txt),
    MD("md", R.string.type_md, R.drawable.ic_type_markdown, R.color.type_md);

    /** MIME types other apps use for this format. */
    val mimeTypes: List<String>
        get() = when (this) {
            PDF -> listOf("application/pdf")
            DOCX -> listOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
            XLSX -> listOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
            TXT -> listOf("text/plain")
            MD -> listOf("text/markdown", "text/x-markdown")
        }

    /** Preferred MIME type handed to "Open in another app". */
    val primaryMime: String get() = mimeTypes.first()

    companion object {
        fun fromKey(key: String): DocType? = values().firstOrNull { it.key == key }
        fun fromMime(mime: String?): DocType? =
            if (mime == null) null else values().firstOrNull { type -> mime in type.mimeTypes }
    }
}

/** Filter chips ("All", "PDF", "Word", "Excel", "Text", "Markdown"). */
enum class TypeFilter(
    @StringRes val chipLabelRes: Int,
    @StringRes val sectionLabelRes: Int,
    val type: DocType?
) {
    ALL(R.string.chip_all, R.string.section_all, null),
    PDF(R.string.chip_pdf, R.string.section_pdf, DocType.PDF),
    DOCX(R.string.chip_word, R.string.section_word, DocType.DOCX),
    XLSX(R.string.chip_excel, R.string.section_excel, DocType.XLSX),
    TXT(R.string.chip_text, R.string.section_text, DocType.TXT),
    MD(R.string.chip_markdown, R.string.section_markdown, DocType.MD);

    companion object {
        val chips: List<TypeFilter> get() = values().toList()
    }
}

enum class SortOption(val key: String, @StringRes val labelRes: Int) {
    NAME_ASC("name_asc", R.string.sort_name_asc),
    NAME_DESC("name_desc", R.string.sort_name_desc),
    DATE_DESC("date_desc", R.string.sort_newest),
    DATE_ASC("date_asc", R.string.sort_oldest),
    SIZE_DESC("size_desc", R.string.sort_largest),
    SIZE_ASC("size_asc", R.string.sort_smallest);

    companion object {
        fun fromKey(key: String): SortOption =
            values().firstOrNull { it.key == key } ?: NAME_ASC

        val options: List<SortOption> get() = values().toList()
    }
}

/** One row of the document registry. */
data class Doc(
    val id: Long = 0L,
    val uri: String,
    val displayName: String,
    val mime: String? = null,
    val type: DocType,
    val size: Long = 0L,
    val dateModified: Long = 0L,
    val starred: Boolean = false,
    val lastOpened: Long = 0L,
    val source: String = DocumentRepository.SOURCE_SCAN
) {
    /** File extension in lower case, without the dot ("" when unknown). */
    val extension: String
        get() {
            val dot = displayName.lastIndexOf('.')
            return if (dot < 0) "" else displayName.substring(dot + 1).lowercase()
        }
}

/** Outcome of a device scan. */
data class ScanResult(
    val count: Int,
    val granted: Boolean,
    val errorMessage: String? = null
)

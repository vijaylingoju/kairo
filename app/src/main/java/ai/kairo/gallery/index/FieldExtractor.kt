package ai.kairo.gallery.index

import ai.kairo.gallery.llm.Prompts
import java.util.Calendar

/**
 * Deterministic checks on top of the LLM:
 * - every LLM field must actually appear in the OCR text (else dropped)
 * - regexes for IDs/booking numbers/seats win over the LLM
 * - rule-based category when the LLM is unavailable
 */
object FieldExtractor {
    private val IC = RegexOption.IGNORE_CASE

    private val PAN = Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b""")
    private val AADHAAR = Regex("""\b[2-9]\d{3}\s?\d{4}\s?\d{4}\b""")
    private val BOOKING_ID = Regex("""BOOKING\s*ID\s*[:\-]?\s*([A-Z0-9]{6,})""", IC)
    private val PNR = Regex("""PNR\s*(?:NO\.?|NUMBER)?\s*[:\-]?\s*(\d{10})""", IC)
    private val SEATS = Regex("""(?:RESERVED|SEATS?)\s*[:\-]\s*([A-Z]{1,2}\d{1,3}(?:\s*,\s*[A-Z]{1,2}\d{1,3})*)""", IC)
    private val TIME = Regex("""\b(\d{1,2}:\d{2}\s?(?:[AP]M)?)\b""", IC)
    private val AMOUNT = Regex("""(?:₹|Rs\.?|INR)\s?([\d,]+(?:\.\d{1,2})?)""", IC)
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")

    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** Keep an LLM value only if it really appears in the OCR text. */
    fun grounded(value: String?, ocr: String): String? {
        if (value.isNullOrBlank() || value.equals("null", ignoreCase = true)) return null
        val n = norm(value)
        return if (n.isNotEmpty() && norm(ocr).contains(n)) value.trim() else null
    }

    /** Rule-based category from OCR text alone (fallback + sanity check). */
    fun guessCategory(ocr: String): String {
        val t = ocr.lowercase()
        return when {
            "income tax" in t || "permanent account number" in t -> "pan_card"
            "aadhaar" in t || "uidai" in t || "aadhar" in t -> "aadhaar_card"
            "driving licence" in t || "driving license" in t -> "driving_license"
            "passport" in t && "republic of india" in t -> "passport"
            "boarding pass" in t || "flight" in t -> "flight_ticket"
            "pnr" in t || "irctc" in t -> "train_ticket"
            "booking id" in t && ("cinema" in t || "theatre" in t || "theater" in t ||
                "pvr" in t || "inox" in t || "ticket(s)" in t) -> "movie_event_ticket"
            "upi" in t || "transaction id" in t || "paid to" in t -> "payment_receipt"
            "invoice" in t || "gstin" in t || "bill" in t -> "bill_invoice"
            else -> "other"
        }
    }

    /** Returns final (category, fields). */
    fun finalize(
        llmCategory: String?,
        llmFields: Map<String, String?>,
        ocr: String,
        dateTakenMs: Long,
    ): Pair<String, Map<String, String?>> {
        val ruleCategory = guessCategory(ocr)
        var category = llmCategory?.takeIf { it in Prompts.CATEGORIES } ?: ruleCategory
        // Strong document signals in OCR override the LLM.
        if (ruleCategory in setOf("pan_card", "aadhaar_card", "driving_license", "passport")) {
            category = ruleCategory
        }

        val out = LinkedHashMap<String, String?>()
        for (k in Prompts.FIELD_KEYS) out[k] = grounded(llmFields[k], ocr)

        // IDs: regex wins.
        val pan = PAN.find(ocr)?.value
        val aadhaar = AADHAAR.find(ocr)?.value
        when {
            category == "pan_card" && pan != null -> out["id_number"] = pan
            category == "aadhaar_card" && aadhaar != null -> out["id_number"] = aadhaar
        }

        // Tickets: regex wins.
        BOOKING_ID.find(ocr)?.groupValues?.get(1)?.let { out["booking_id"] = it }
        PNR.find(ocr)?.groupValues?.get(1)?.let { out["booking_id"] = it }
        SEATS.find(ocr)?.groupValues?.get(1)?.let { out["seats"] = it }

        // Fill gaps only.
        if (out["time"] == null) out["time"] = TIME.find(ocr)?.groupValues?.get(1)
        if (out["amount"] == null) out["amount"] = AMOUNT.find(ocr)?.groupValues?.get(1)

        // "Wed, 23 Sep" has no year -> take it from when the image was captured.
        out["date"] = withYear(out["date"], dateTakenMs)

        return category to out
    }

    private fun withYear(date: String?, dateTakenMs: Long): String? {
        if (date == null) return null
        if (YEAR.containsMatchIn(date)) return date
        val year = Calendar.getInstance().apply { timeInMillis = dateTakenMs }.get(Calendar.YEAR)
        return "$date $year"
    }
}

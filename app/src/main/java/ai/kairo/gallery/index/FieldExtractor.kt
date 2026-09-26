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

    val ID_CATEGORIES = setOf("pan_card", "aadhaar_card", "driving_license", "passport")

    private val PAN = Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b""")
    private val AADHAAR = Regex("""\b[2-9]\d{3}\s?\d{4}\s?\d{4}\b""")
    // DigiLocker shows "xxxxxxxx8271"; OCR may glue it to the gender ("Malexxx8271").
    private val AADHAAR_MASKED = Regex("""[xX*]{3,}\s?(\d{4})\b""")
    // Train berth as printed in the status line: "CNF/B2/34".
    private val TRAIN_BERTH = Regex("""\bCNF\s*/\s*([A-Z]{1,2}\d{1,2})\s*/\s*(\d{1,3})""", IC)
    private val BOOKING_ID = Regex("""BOOKING\s*ID\s*[:\-]?\s*([A-Z0-9]{6,})""", IC)
    // Train PNRs are 10 digits; airline/bus PNRs are alphanumeric. Must contain a digit.
    private val PNR = Regex("""PNR\s*(?:NO\.?|NUMBER)?\s*[:\-]?\s*((?=[A-Z0-9]*\d)[A-Z0-9]{6,16})\b""", IC)
    private val UPI_TXN = Regex("""(?:UPI\s*)?(?:transaction|txn|ref(?:erence)?)\s*(?:ID|No\.?)?\s*[:\-]?\s*\n?\s*(\d{12})\b""", IC)
    // "Coach/Seat: B2 / 34" (train), "Seat: 14C" (flight), "Seats: A1, A2" (generic)
    private val SEATS = Regex(
        """(?:COACH\s*/\s*SEAT|BERTH|RESERVED|SEATS?)\s*(?:NO\.?)?\s*[:\-]\s*""" +
            """([A-Z]{1,2}\d{1,2}\s*[/Il|]\s*\d{1,3}|\d{1,2}[A-K]\b|[A-Z]{1,2}\d{1,3}(?:\s*,\s*[A-Z]{1,2}\d{1,3})*)""",
        IC,
    )
    // Movie apps print seats as "BALCONY - N10, N11, N9" / "PREMIUM - H13, H14" on their own line.
    private val MOVIE_SEATS = Regex("""(?m)^([A-Z][A-Z0-9 ]{2,30}\s-\s[A-Z]{1,2}\d{1,3}(?:\s*,\s*[A-Z]{1,2}\d{1,3})*)\s*$""")
    // "Fri, 07 Aug | 09:30 PM"
    private val SHOW_TIME = Regex("""\b((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),\s*\d{1,2}\s+[A-Z][a-z]{2})\s*\|\s*(\d{1,2}:\d{2}\s?[AP]M)""")
    private val FLIGHT = Regex("""FLIGHT\s*(?:NO\.?)?\s*[:\-]?\s*([A-Z0-9]{2}\s?\d{2,4})\b""", IC)
    private val DATE = Regex(
        """\b(\d{1,2}[-/ ](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|[O0]ct|Nov|Dec)[a-z]*[-/ ]\d{2,4}|\d{1,2}/\d{1,2}/\d{4})\b""",
        IC,
    )
    private val TIME = Regex("""\b(\d{1,2}:\d{2}(?: ?[AP]M)?)\b""", IC)
    private val AMOUNT = Regex("""(?:₹|Rs\.?|INR)\s?([\d,]+(?:\.\d{1,2})?)""", IC)
    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private val BUS_SIGNALS = Regex("""\b(bus|redbus|abhibus|intrcity|apsrtc|tsrtc|ksrtc|boarding point|dropping point)\b""", IC)
    private val TRAIN_SIGNALS = Regex("""\b(irctc|train no|coach|berth|railway|express)\b""", IC)

    private fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** Keep an LLM value only if it really appears in the OCR text. */
    fun grounded(value: String?, ocr: String): String? {
        if (value.isNullOrBlank() || value.equals("null", ignoreCase = true)) return null
        val n = norm(value)
        return if (n.isNotEmpty() && norm(ocr).contains(n)) value.trim() else null
    }

    /**
     * OCR confuses the letter O with zero inside numbers ("SRYROO01009831" for "SRYR0001009831").
     * Inside a run of O/0/digits that contains a real digit, O can only be a zero.
     */
    fun fixDigitOs(id: String): String =
        Regex("""[O0-9]{2,}""").replace(id) { m -> if (m.value.any { it.isDigit() }) m.value.replace('O', '0') else m.value }

    /** Rule-based category from OCR text alone (fallback + sanity check). */
    fun guessCategory(ocr: String): String {
        val t = ocr.lowercase()
        return when {
            "income tax" in t || "permanent account number" in t -> "pan_card"
            "aadhaar" in t || "uidai" in t || "aadhar" in t -> "aadhaar_card"
            "driving licence" in t || "driving license" in t -> "driving_license"
            "passport" in t && "republic of india" in t -> "passport"
            "boarding pass" in t || "flight" in t -> "flight_ticket"
            "pnr" in t && BUS_SIGNALS.containsMatchIn(ocr) && !TRAIN_SIGNALS.containsMatchIn(ocr) -> "bus_ticket"
            "pnr" in t || "irctc" in t -> "train_ticket"
            "booking id" in t && ("cinema" in t || "theatre" in t || "theater" in t ||
                "pvr" in t || "inox" in t || "ticket(s)" in t) -> "movie_event_ticket"
            "invoice" in t || "gstin" in t -> "bill_invoice"
            "upi" in t || "transaction id" in t || "paid to" in t -> "payment_receipt"
            "bill" in t -> "bill_invoice"
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
        if (ruleCategory in ID_CATEGORIES) category = ruleCategory
        // The LLM calls every PNR ticket a train ticket; bus apps say "bus"/"boarding point" and never "coach".
        if (category == "train_ticket" && ruleCategory == "bus_ticket") category = "bus_ticket"

        val out = LinkedHashMap<String, String?>()
        for (k in Prompts.FIELD_KEYS) out[k] = grounded(llmFields[k], ocr)

        // ID numbers only make sense on ID cards (a bill's GSTIN or a ticket's PNR is not "my ID number").
        if (category !in ID_CATEGORIES) out["id_number"] = null

        // IDs: only a number that has the right shape counts (never the LLM's copy of nearby text).
        when (category) {
            "pan_card" -> out["id_number"] = PAN.find(ocr)?.value
            "aadhaar_card" -> out["id_number"] = AADHAAR.find(ocr)?.value
                ?: AADHAAR_MASKED.find(ocr)?.let { "XXXX XXXX ${it.groupValues[1]}" }
        }

        // Tickets / payments: regex wins.
        BOOKING_ID.find(ocr)?.groupValues?.get(1)?.let { out["booking_id"] = it }
        PNR.find(ocr)?.groupValues?.get(1)?.let { out["booking_id"] = it }
        if (category == "payment_receipt") UPI_TXN.find(ocr)?.groupValues?.get(1)?.let { out["booking_id"] = it }
        SEATS.find(ocr)?.groupValues?.get(1)?.let { out["seats"] = it.trim().replace(Regex("""\s*[/Il|]\s*(?=\d+$)"""), " / ") }
        TRAIN_BERTH.find(ocr)?.let { out["seats"] = "${it.groupValues[1]} / ${it.groupValues[2]}" }
        if (out["seats"] == null && category == "movie_event_ticket") {
            MOVIE_SEATS.find(ocr)?.groupValues?.get(1)?.let { out["seats"] = it.trim() }
        }
        SHOW_TIME.find(ocr)?.let { m ->
            if (out["date"] == null) out["date"] = m.groupValues[1]
            out["time"] = m.groupValues[2]  // the show time beats any other clock on the page
        }
        if (category == "flight_ticket") {
            FLIGHT.find(ocr)?.groupValues?.get(1)?.let { f ->
                if (out["title"] == null || out["title"].equals("BOARDING PASS", ignoreCase = true)) out["title"] = "Flight $f"
            }
        }

        // Fill gaps only.
        if (out["date"] == null && category !in ID_CATEGORIES) {
            out["date"] = DATE.find(ocr)?.groupValues?.get(1)?.replace(Regex("""(?<=\d[-/ ])0(?=ct)"""), "O")  // "15-0ct" -> "15-Oct"
        }
        if (out["time"] == null) out["time"] = TIME.find(ocr)?.groupValues?.get(1)
        if (out["amount"] == null) out["amount"] = AMOUNT.find(ocr)?.groupValues?.get(1)

        // O -> 0 inside numbers of IDs (after grounding, which compares against the raw OCR).
        for (k in listOf("booking_id", "id_number")) out[k] = out[k]?.let(::fixDigitOs)
        for (k in out.keys) out[k] = out[k]?.trim()?.takeIf { it.isNotEmpty() }

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

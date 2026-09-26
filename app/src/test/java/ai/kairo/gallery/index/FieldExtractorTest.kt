package ai.kairo.gallery.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cases taken from real OCR output seen on the iQOO 15 during the 2026-09-26 evaluation. */
class FieldExtractorTest {
    private val sep2026 = 1_790_000_000_000L

    @Test
    fun letterOInsideNumbersBecomesZero() {
        assertEquals("SRYR0001009831", FieldExtractor.fixDigitOs("SRYROO01009831"))
        assertEquals("WPRQ24R", FieldExtractor.fixDigitOs("WPRQ24R"))      // no O near digits
        assertEquals("ABCPT1234K", FieldExtractor.fixDigitOs("ABCPT1234K"))
        assertEquals("BOOKING", FieldExtractor.fixDigitOs("BOOKING"))      // O's without digits stay letters
    }

    @Test
    fun busTicketIsNotATrainTicket() {
        val ocr = "1916| Rajahmundry To\nHyderabad\nPNR:IC276454472513\nLive Track Bus\n<oarding Point Dropping Point\nINTRCITY"
        assertEquals("bus_ticket", FieldExtractor.guessCategory(ocr))
        val (cat, f) = FieldExtractor.finalize("train_ticket", mapOf("id_number" to "IC276454472513"), ocr, sep2026)
        assertEquals("bus_ticket", cat)
        assertEquals("IC276454472513", f["booking_id"])
        assertNull("a PNR is not an ID number", f["id_number"])
    }

    @Test
    fun realTrainTicketStaysTrain() {
        val ocr = "IRCTC Electronic Reservation Slip (ERS)\nPNR No: 4521367890\nTrain No./Name: 12727 / GODAVARI EXPRESS\n" +
            "Date of Journey: 15-Oct-2026 Departure: 18:45\nCoach/Seat: B2 / 34\nTotal Fare: Rs. 1085.00"
        val (cat, f) = FieldExtractor.finalize("train_ticket", emptyMap(), ocr, sep2026)
        assertEquals("train_ticket", cat)
        assertEquals("4521367890", f["booking_id"])
        assertEquals("B2 / 34", f["seats"])
        assertEquals("15-Oct-2026", f["date"])
    }

    @Test
    fun boardingPassSeatAndAlphanumericPnr() {
        val ocr = "KAIRO AIRWAYS\nFlight: KA 2345\nDate: 18 Oct 2026 Boarding: 07:10 Departure: 07:40\nGate: 22 Seat: 14C\nPNR: KX7Q2M\nBOARDING PASS"
        val (cat, f) = FieldExtractor.finalize("flight_ticket", mapOf("title" to "BOARDING PASS"), ocr, sep2026)
        assertEquals("flight_ticket", cat)
        assertEquals("14C", f["seats"])
        assertEquals("KX7Q2M", f["booking_id"])
        assertEquals("Flight KA 2345", f["title"])
    }

    @Test
    fun movieTicketSeatsAndShowTimeFromOcr() {
        val ocr = "20:16\nDC (Telugu) (A)\nTelugu, 2D\nFri, 07 Aug | 09:30 PM\n2 Ticket(s)\nSURYA PALACE\nPREMIUM - H13, H14\nBOOKING ID:\nSRYROO01009831"
        val (_, f) = FieldExtractor.finalize("movie_event_ticket", mapOf("time" to "20:16"), ocr, sep2026)
        assertEquals("PREMIUM - H13, H14", f["seats"])
        assertEquals("09:30 PM", f["time"])          // show time, not the status-bar clock
        assertEquals("Fri, 07 Aug 2026", f["date"])
        assertEquals("SRYR0001009831", f["booking_id"])
    }

    @Test
    fun billGstinIsNotAnIdAndUpiMentionKeepsBillCategory() {
        val ocr = "TEST BIRYANI HOUSE\nGSTIN: 36AAAAA0000A1Z5\nTAX INVOICE\nBill No: 4417 Date: 12/09/2026 Time: 21:14\nGrand Total Rs. 1245.50\nPaid by UPI. Thank you"
        assertEquals("bill_invoice", FieldExtractor.guessCategory(ocr))
        val (_, f) = FieldExtractor.finalize("bill_invoice", mapOf("id_number" to "36AAAAA0000A1Z5"), ocr, sep2026)
        assertNull(f["id_number"])
        assertEquals("1245.50", f["amount"])
    }

    @Test
    fun upiTransactionId() {
        val ocr = "Payment successful\nRs. 350\nPaid to\nRavi Test Stores\nUPI transaction ID\n425361789012\nDate: 18 Sep 2026, 7:42 PM"
        val (cat, f) = FieldExtractor.finalize("payment_receipt", emptyMap(), ocr, sep2026)
        assertEquals("payment_receipt", cat)
        assertEquals("425361789012", f["booking_id"])
        assertEquals("350", f["amount"])
    }

    @Test
    fun trainTicketAsActuallyReadByMlKit() {
        // Real OCR of the synthetic ticket: "Oct" read as "0ct", "B2 / 34" read as "B2I 34".
        val ocr = "IRCTC Electronic Reservation Slip (ERS)\nPNR No: 4521367890\nDate of Journey: 15-0ct-2026\n" +
            "Coach/Seat: B2I 34\nDeparture: 18:45\nPassenger: TEST PASSENGER Age: 25 Status: CNF/B2/34"
        val (_, f) = FieldExtractor.finalize("train_ticket", mapOf("seats" to "B2"), ocr, sep2026)
        assertEquals("B2 / 34", f["seats"])
        assertEquals("15-Oct-2026", f["date"])
    }

    @Test
    fun maskedAadhaarNeverTakesTheLlmsGuess() {
        val ocr = "GOVERNMENT OF INDIA\nTest User Name\n2004-01-26\nMalexxx8271\nAADHAAR"
        val (cat, f) = FieldExtractor.finalize("aadhaar_card", mapOf("id_number" to "Malexxx8271"), ocr, sep2026)
        assertEquals("aadhaar_card", cat)
        assertEquals("XXXX XXXX 8271", f["id_number"])
    }

    @Test
    fun valuesHaveNoTrailingNewlines() {
        val (_, f) = FieldExtractor.finalize("other", emptyMap(), "Meeting at 12:13\nroom 4", sep2026)
        assertEquals("12:13", f["time"])
    }
}

package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper

import android.util.Log

data class CnicFields(
    val name: String?,
    val nameUrdu: String?,
    val fatherName: String?,
    val fatherNameUrdu: String?,
    val gender: String?,          // "M" or "F"
    val countryOfStay: String?,
    val identityNumber: String?,
    val dateOfBirth: String?,
    val dateOfIssue: String?,
    val dateOfExpiry: String?,
    val debugBlocks: Map<String, String> // block label -> raw text used, for troubleshooting
)

/**
 * Simplified parser.
 *
 * Strategy:
 * - Fields with a UNIQUE, unmistakable shape (CNIC number, dates) are pulled straight out of
 *   the FULL raw text with regex. No anchor/label detection needed — this makes them immune
 *   to OCR label typos like "Identity" -> "ldentity".
 * - Fields with NO fixed shape (Name, Father Name, Gender, Country) still use a light anchor
 *   lookup, since there's no pattern to regex against. These stay simple: find the label line,
 *   take the very next non-empty line(s).
 */
object CnicFieldParser {

    // Matches DD.MM.YYYY, DD/MM/YYYY, or DD,MM,YYYY even if concatenated back-to-back
    // private val DATE_REGEX = Regex("""(?<!\d)(\d{1,2})[.,\-/](\d{1,2})[.,\-/](\d{4})""")
    // Matches DD<sep>MM<sep>YYYY where <sep> can be . , - / \  — even when dates are
// jammed together back-to-back or surrounded by OCR noise letters, since we don't
// require a non-digit boundary before/after the match (the separators do that job).
    private val DATE_REGEX = Regex("""(\d{1,2})[.,\-/\\](\d{1,2})[.,\-/\\](\d{4})""")

    // Updated Regex: includes comma (,) alongside . - /
    private val IDENTITY_REGEX = Regex("""\b\d{5}-\d{7}-\d\b""")

    //private val GENDER_REGEX = Regex("""\b(Male|Female|M|F)\b""", RegexOption.IGNORE_CASE)
    private val GENDER_REGEX = Regex(
        """(Male|Female|\bM\b|\bF\b|(?<=[a-z])M(?=[A-Z\s]|$)|(?<=[a-z])F(?=[A-Z\s]|$)|^M(?=[A-Z][a-z])|^F(?=[A-Z][a-z]))""",
        RegexOption.IGNORE_CASE
    )

    fun parse(result: CardOcrResult): CnicFields {
        val lines = result.lineCrops
        val debug = mutableMapOf<String, String>()

        // Whole-card text blob — used for pattern-based fields (identity number, dates).
        val fullText = lines.joinToString(" ") { it.recognizedText }
        debug["fullText"] = fullText

        fun clean(s: String) = s.lowercase().replace(" ", "").replace("\n", "").trim()

        // ---- Identity number: unique pattern, search the whole card, no anchor needed ----
        val identityNumber = IDENTITY_REGEX.find(fullText)?.value
        debug["identityNumber"] = identityNumber.orEmpty()

        // ---- All dates on the card: there are always exactly 3 (DOB, Issue, Expiry). ----
        // Sorting ascending gives DOB (earliest) -> Issue -> Expiry (latest) directly,
        // since a person's DOB always precedes issue date, which always precedes expiry.
        val allDates = DATE_REGEX.findAll(fullText)
            .mapNotNull { m ->
                val day = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull()
                val year = m.groupValues[3].toIntOrNull()
                if (day != null && month != null && year != null) {
                    // Validate against realistic calendar ranges to drop invalid OCR noise like 1234 or 1245
                    if (day in 1..31 && month in 1..12 && year in 1740..9050) {
                        val sortKey = year * 10000 + month * 100 + day
                        sortKey to "%02d.%02d.%04d".format(day, month, year)
                    } else null
                } else null
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
        debug["allDates"] = allDates.joinToString(", ")

        val dateOfBirth = allDates.getOrNull(0)
        val dateOfIssue = allDates.getOrNull(1)
        val dateOfExpiry = allDates.getOrNull(2)

        // ---- Name / Father Name: no fixed pattern, so still anchor-based ----
        val nameIdx = lines.indexOfFirst { clean(it.recognizedText) == "name" }
        val fatherIdx = lines.indexOfFirst { clean(it.recognizedText).contains("father") }

        val name = nameIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        val fatherName = fatherIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        debug["name"] = name.orEmpty()
        debug["fatherName"] = fatherName.orEmpty()

        // ---- Gender / Country: anchor-based block extraction ----
        val genderCountryIdx = lines.indexOfFirst {
            val c = clean(it.recognizedText)
            c.contains("gender") || (c.contains("country") && c.contains("stay"))
        }
        // Look a few lines past the anchor for gender + country
        val genderCountryBlock = if (genderCountryIdx in lines.indices) {
            (genderCountryIdx + 1..minOf(genderCountryIdx + 3, lines.size - 1))
                .joinToString(" ") { lines[it].recognizedText }
        } else ""
        debug["genderCountryBlock"] = genderCountryBlock

// 1. Find Gender match and determine 'M' or 'F'
        val genderMatch = GENDER_REGEX.find(genderCountryBlock)
        val gender = genderMatch?.value?.let {
            if (it.startsWith("M", ignoreCase = true)) "M" else "F"
        }

// 2. Remove the matched Gender string from the block (handles "PakistanM" -> "Pakistan", "MPakistan" -> "Pakistan")
        val cleanedBlock = if (genderMatch != null) {
            genderCountryBlock.replace(genderMatch.value, "", ignoreCase = true)
        } else {
            genderCountryBlock
        }

// 3. Extract Country of Stay safely from the cleaned block
        val GENDER_COUNTRY_STOPWORDS =
            setOf("gender", "country", "of", "stay", "m", "f", "male", "female")

//        val countryOfStay = cleanedBlock
//            .split(Regex("[^a-zA-Z]+")) // Split on any non-alphabetic character (spaces, symbols, punctuation)
//            .map { it.trim() }
//            .firstOrNull { word ->
//                word.length > 2 && word.lowercase() !in GENDER_COUNTRY_STOPWORDS
//            }
        val countryOfStay = cleanedBlock
            .split(Regex("[^a-zA-Z]+"))
            .map { it.trim() }
            .firstOrNull { word ->
                word.length > 2 && word.lowercase() !in GENDER_COUNTRY_STOPWORDS
            }
            ?.let { rawCountry ->
                // 1. Hardened check for Pakistan
                if (rawCountry.contains(
                        "pakistan",
                        ignoreCase = true
                    ) || rawCountry.startsWith("Pak", ignoreCase = true)
                ) {
                    "Pakistan"
                } else {
                    // 2. Remove trailing repeated OCR artifact characters (e.g. "Ooo", "123", "xxx")
                    rawCountry.replace(Regex("([a-zA-Z])\\1{2,}$"), "$1")
                }
            }

        Log.d(
            "CnicFieldParser",
            "name=$name father=$fatherName gender=$gender country=$countryOfStay " +
                    "identity=$identityNumber dob=$dateOfBirth issue=$dateOfIssue expiry=$dateOfExpiry"
        )
        Log.d(
            "CnicFieldParser",
            "fullText=${fullText}"
        )

        return CnicFields(
            name = name,
            nameUrdu = result.urduNameCrop?.recognizedText,
            fatherName = fatherName,
            fatherNameUrdu = result.urduFatherNameCrop?.recognizedText,
            gender = gender,
            countryOfStay = countryOfStay,
            identityNumber = identityNumber,
            dateOfBirth = dateOfBirth,
            dateOfIssue = dateOfIssue,
            dateOfExpiry = dateOfExpiry,
            debugBlocks = debug
        )
    }
}


/*
package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper


import android.util.Log

data class CnicFields(
    val name: String?,
    val nameUrdu: String?,
    val fatherName: String?,
    val fatherNameUrdu: String?,
    val gender: String?,          // "M" or "F"
    val countryOfStay: String?,
    val identityNumber: String?,
    val dateOfBirth: String?,
    val dateOfIssue: String?,
    val dateOfExpiry: String?,
    val debugBlocks: Map<String, String> // block label -> raw text used, for troubleshooting
)

object CnicFieldParser {

    private val DATE_REGEX = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b""")
    private val IDENTITY_REGEX = Regex("""\b\d{5}-\d{7}-\d\b""")
    private val GENDER_REGEX = Regex("""\b(Male|Female|M|F)\b""", RegexOption.IGNORE_CASE)

    private val GENDER_COUNTRY_STOPWORDS =
        setOf("gender", "country", "of", "stay", "male", "female", "m", "f")

    fun parse(result: CardOcrResult): CnicFields {
        val lines = result.lineCrops // already top-to-bottom order, one entry per ML Kit line
        val debug = mutableMapOf<String, String>()

        fun clean(s: String) = s.lowercase().replace(" ", "").replace("\n", "").trim()
        fun textOfRange(start: Int, endExclusive: Int): String =
            if (start < 0) "" else (start until endExclusive.coerceAtMost(lines.size))
                .joinToString(" ") { lines[it].recognizedText }

        // ---- 1. Locate anchor line indices ----
        val nameIdx = lines.indexOfFirst { clean(it.recognizedText) == "name" }
        val fatherIdx = lines.indexOfFirst { clean(it.recognizedText).contains("father") }
        val genderCountryIdx = lines.indexOfFirst {
            val c = clean(it.recognizedText)
            c.contains("gender") || (c.contains("country") && c.contains("stay"))
        }
        val identityIdx = lines.indexOfFirst {
            clean(it.recognizedText).contains("identity")
                    || clean(it.recognizedText).contains("number")
                    || clean(it.recognizedText).contains("birth")
        }
        val issueIdx = lines.indexOfFirst { clean(it.recognizedText).contains("issue") }
        val expiryIdx = lines.indexOfFirst {
            val c = clean(it.recognizedText)
            c.contains("expiry") || c.contains("expire")
        }

        // ---- 2. Simple value-next-to-label fields (Name / Father Name) ----
        val name = nameIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        val fatherName = fatherIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        debug["name"] = name.orEmpty()
        debug["fatherName"] = fatherName.orEmpty()

        // ---- 3. Gender / Country block: from genderCountryIdx up to whichever of
        //         Identity / Issue comes next (order on the card can vary) ----
        val genderBlockEnd = listOfNotNull(
            identityIdx.takeIf { it > genderCountryIdx },
            issueIdx.takeIf { it > genderCountryIdx }
        ).minOrNull() ?: lines.size
        val genderBlockText = textOfRange(genderCountryIdx, genderBlockEnd)
        debug["genderCountryBlock"] = genderBlockText

        val gender = GENDER_REGEX.find(genderBlockText)?.value?.let {
            if (it.startsWith("M", ignoreCase = true)) "M" else "F"
        }
        val countryOfStay = genderBlockText
            .split(Regex("\\s+"))
            .map { it.trim() }
            .firstOrNull { w ->
                w.length > 2 && clean(w) !in GENDER_COUNTRY_STOPWORDS && !GENDER_REGEX.matches(
                    w
                )
            }

        // ---- 4. Identity Number / Date of Birth block: from identityIdx up to whichever
        //         of Gender/Country or Issue comes next ----
        val identityBlockEnd = listOfNotNull(
            genderCountryIdx.takeIf { it > identityIdx },
            issueIdx.takeIf { it > identityIdx }
        ).minOrNull() ?: lines.size
        val identityBlockText = textOfRange(identityIdx, identityBlockEnd)
        debug["identityBlock"] = identityBlockText

        val identityNumber = IDENTITY_REGEX.find(identityBlockText)?.value
        val dateOfBirth = DATE_REGEX.find(identityBlockText)?.value

        // ---- 5. Issue / Expiry block: from whichever of issueIdx/expiryIdx comes first,
        //         to end of detected lines — collect ALL dates, smaller = issue, larger = expiry ----
        val datesBlockStart =
            listOfNotNull(issueIdx.takeIf { it >= 0 }, expiryIdx.takeIf { it >= 0 }).minOrNull()
                ?: -1
        val datesBlockText = textOfRange(datesBlockStart, lines.size)
        debug["issueExpiryBlock"] = datesBlockText
//        debug["textData"] = line

        val parsedDates = DATE_REGEX.findAll(datesBlockText)
            .mapNotNull { m ->
                val day = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull()
                val year = m.groupValues[3].toIntOrNull()
                if (day != null && month != null && year != null) {
                    val sortKey = year * 10000 + month * 100 + day
                    Triple(sortKey, "%02d.%02d.%04d".format(day, month, year), m.value)
                } else null
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .toList()

        val dateOfIssue = parsedDates.getOrNull(0)?.second
        val dateOfExpiry = parsedDates.getOrNull(1)?.second

        Log.d(
            "CnicFieldParser",
            "name=$name father=$fatherName gender=$gender country=$countryOfStay " +
                    "identity=$identityNumber dob=$dateOfBirth issue=$dateOfIssue expiry=$dateOfExpiry"
        )
       lines.forEach {
           Log.d(
               "CnicFieldParser",
               " \n${it.label} = ${it.recognizedText}"
           )
       }

        return CnicFields(
            name = name,
            nameUrdu = result.urduNameCrop?.recognizedText,
            fatherName = fatherName,
            fatherNameUrdu = result.urduFatherNameCrop?.recognizedText,
            gender = gender,
            countryOfStay = countryOfStay,
            identityNumber = identityNumber,
            dateOfBirth = dateOfBirth,
            dateOfIssue = dateOfIssue,
            dateOfExpiry = dateOfExpiry,
            debugBlocks = debug
        )
    }
}*/

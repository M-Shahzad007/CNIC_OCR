package pk.pitb.cnic_ocr_detection.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.leptonica.android.AdaptiveMap
import com.googlecode.leptonica.android.Binarize
import com.googlecode.leptonica.android.Convert
import com.googlecode.leptonica.android.Enhance
import com.googlecode.leptonica.android.Pix
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.leptonica.android.Rotate
import com.googlecode.leptonica.android.Skew
import com.googlecode.leptonica.android.WriteFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class Utils {

    companion object {
        fun parseCnicTextToJson(rawText: String): JSONObject {
            val json = JSONObject()
            Log.d("rawData", rawText)
            try {
                val dateRegexStrict = "^\\d{2}[., ]\\d{2}[., ]\\d{4}$"
                val dateRegexLoose = ".*\\d{2}[., ]\\d{2}[., ]\\d{4}.*"
                val cnicRegex = "\\d{5}[- ]\\d{7}[- ]\\d"

                // Split lines and trim each
                val lines = rawText.split("\\n".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                for (i in lines.indices) {
                    val line = lines[i].trim { it <= ' ' }

                    //Old CNIC
                    if (line.lowercase(Locale.getDefault()).trim { it <= ' ' }
                            .matches(cnicRegex.toRegex())) {
                        json.put("cnic_number", line.trim { it <= ' ' }.replace(" ", "-"))
                    }

                    val name = extractName(line, lines, i)
                    val fatherName = extractFatherName(line, lines, i)

                    if (name != null) {
                        json.put("name", name)
                    }
                    if (fatherName != null) {
                        json.put("father_name", fatherName)
                    }

                    // CNIC & DOB
                    if ((line.lowercase(Locale.getDefault())
                            .contains("identity number") || line.lowercase(
                            Locale.getDefault()
                        ).contains("ldentity number") || line.lowercase(Locale.getDefault())
                            .contains("number")
                                || line.lowercase(Locale.getDefault())
                            .contains("identlty") || line.lowercase(Locale.getDefault())
                            .contains("ldentlty") || line.lowercase(
                            Locale.getDefault()
                        ).contains("nymber") || line.lowercase(Locale.getDefault())
                            .contains("date of birth") || line.lowercase(
                            Locale.getDefault()
                        ).contains("birth")
                                || line.lowercase(Locale.getDefault())
                            .contains("blrth")) && (i + 1 < lines.size)
                    ) {
                        if (line.contains(" ")) {
                            val parts =
                                lines[i + 1].split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                                    .toTypedArray()
                            for (j in parts.indices) {
                                if (parts[j].matches(cnicRegex.toRegex())) {
                                    json.put("cnic_number", parts[j])
                                }
                                if (j + 1 < parts.size) {
                                    if ((parts[j + 1].matches(dateRegexStrict.toRegex()) || parts[j + 1].matches(
                                            dateRegexLoose.toRegex()
                                        ))
                                    ) {
                                        json.put(
                                            "date_of_birth", parts[j + 1]
                                                .replace("[^\\d., ]".toRegex(), "")
                                                .replace("[., ]+$".toRegex(), "")
                                                .replace(",", ".")
                                        )
                                    }
                                }
                            }
                        } else if (lines[i + 1].matches(cnicRegex.toRegex())) {
                            json.put("cnic_number", lines[i + 1])
                        } else if (lines[i + 2].matches(cnicRegex.toRegex())) {
                            json.put("cnic_number", lines[i + 2])
                        }
                    }

                    val dob = extractDate(line, lines, i, "date_of_birth", json)
                    val doi = extractDate(line, lines, i, "date_of_issue", json)
                    val doe = extractDate(line, lines, i, "date_of_expiry", json)

                    if (dob != "N/A" && isDateBelowToday(dob)) {
                        json.put("date_of_birth", dob)
                    }
                    if (doi != "N/A") {
                        json.put("date_of_issue", doi)
                    }
                    if (doe != "N/A") {
                        json.put("date_of_expiry", doe)
                    }

                    // Gender
                    if (line.lowercase(Locale.getDefault())
                            .contains("gender") || line.lowercase(Locale.getDefault())
                            .contains("gendee")
                        || line.lowercase(Locale.getDefault())
                            .contains("gende") || line.lowercase(
                            Locale.getDefault()
                        ).contains("gendes") || line.lowercase(
                            Locale.getDefault()
                        ).contains("gend")
                    ) {
                        if (lines[i + 1].contains("M") || lines[i + 2].contains("M")) {
                            json.put("gender", "Male")
                        } else if (lines[i + 1].contains("F") || lines[i + 2].contains("F")) {
                            json.put("gender", "Female")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Log.d("parsed data", json.toString())
            return json
        }

        fun isDateBelowToday(dob: String?): Boolean {
            val sdf = SimpleDateFormat("dd.MM.yyyy")
            sdf.isLenient = false

            try {
                val dateOfBirth = sdf.parse(dob)
                val today = Date()

                return dateOfBirth.before(today)
            } catch (e: ParseException) {
                e.printStackTrace()
                return false
            }
        }

        fun extractName(line: String, lines: Array<String>, i: Int): String? {
//        String nameRegex = "^(?![A-Z ]+$)[A-Za-z ]+$";
            if ((line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("name") || line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("narme") || line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("nome") || line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("narm") || line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("neme") || line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("nare")) && !line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("father") && !line.lowercase(Locale.getDefault())
                    .trim { it <= ' ' }.contains("facher") && (i + 1 < lines.size)
            ) {
                if (!lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("pakistan")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("pak")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("islamic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("lslamic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("republic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("gender")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("gendee")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("country")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("stay")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("date")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("identity")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("ldentity")
                ) {
                    return lines[i + 1].trim { it <= ' ' }.replace("[^A-Za-z ]".toRegex(), "")
                }
                return "N/A"
            }
            return null
        }

        fun extractFatherName(line: String, lines: Array<String>, i: Int): String? {
            if ((line.lowercase(Locale.getDefault())
                    .contains("father") || line.lowercase(Locale.getDefault())
                    .contains("facher") || line.lowercase(
                    Locale.getDefault()
                ).contains("fatber"))
                && (i + 1 < lines.size)
            ) {
                if (!lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("pakistan")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("pak")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("islamic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("lslamic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("republic")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("gender")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("gendee")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("country")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("stay")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("date")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("identity")
                    && !lines[i + 1].trim { it <= ' ' }.lowercase(Locale.getDefault())
                        .contains("ldentity")
                ) {
                    return lines[i + 1].trim { it <= ' ' }.replace("[^A-Za-z ]".toRegex(), "")
                }
                return "N/A"
            }
            return null
        }

        fun extractDate(
            line: String,
            lines: Array<String>,
            i: Int,
            type: String,
            json: JSONObject
        ): String {
            val dateRegexStrict = "^\\d{2}[., ]\\d{2}[., ]\\d{4}$"
            val dateRegexLoose = ".*\\d{2}[., ]\\d{2}[., ]\\d{4}.*"
            var dateToSend = "N/A"

            if (type.equals("date_of_birth", ignoreCase = true)) {
                if ((line.lowercase(Locale.getDefault())
                        .contains("date of birth") || line.lowercase(
                        Locale.getDefault()
                    ).contains("birth") || line.lowercase(
                        Locale.getDefault()
                    ).contains("blrth"))
                    && !json.has(type)
                ) {
                    var date = ""
                    if ((lines[i + 1].matches(dateRegexStrict.toRegex()) || lines[i + 1].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 1 < lines.size)
                    ) {
                        date = lines[i + 1].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    } else if ((lines[i + 2].matches(dateRegexStrict.toRegex()) || lines[i + 2].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 2 < lines.size)
                    ) {
                        date = lines[i + 2].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    }

                    val strictPattern = Pattern.compile(dateRegexStrict)
                    val matcher = strictPattern.matcher(date)

                    if (matcher.find()) {
                        dateToSend = matcher.group()
                    }
                }
            } else if (type.equals("date_of_issue", ignoreCase = true)) {
                if ((line.lowercase(Locale.getDefault())
                        .contains("date of issue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("date of 1ssue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("date of lssue")
                            || line.lowercase(Locale.getDefault())
                        .contains("lssue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("issue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("tssue")
                            || line.lowercase(Locale.getDefault())
                        .contains("ssue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("ssve") || line.lowercase(
                        Locale.getDefault()
                    ).contains("sue")
                            || line.lowercase(Locale.getDefault())
                        .contains("s\$ue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("\$sue") || line.lowercase(
                        Locale.getDefault()
                    ).contains("ssu"))
                ) {
                    var date = ""
                    if ((lines[i + 1].matches(dateRegexStrict.toRegex()) || lines[i + 1].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 1 < lines.size)
                    ) {
                        date = lines[i + 1].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    } else if ((lines[i + 2].matches(dateRegexStrict.toRegex()) || lines[i + 2].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 2 < lines.size)
                    ) {
                        date = lines[i + 2].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    }

                    val strictPattern = Pattern.compile(dateRegexStrict)
                    val matcher = strictPattern.matcher(date)

                    if (matcher.find()) {
                        dateToSend = matcher.group()
                    }
                }
            } else if (type.equals("date_of_expiry", ignoreCase = true)) {
                if ((line.lowercase(Locale.getDefault())
                        .contains("date of expiry") || line.lowercase(
                        Locale.getDefault()
                    ).contains("expiry") || line.lowercase(
                        Locale.getDefault()
                    ).contains("enpiry")
                            || line.lowercase(Locale.getDefault())
                        .contains("npiry") || line.lowercase(
                        Locale.getDefault()
                    ).contains("piry") || line.lowercase(
                        Locale.getDefault()
                    ).contains("plry"))
                ) {
                    var date = ""
                    if ((lines[i + 1].matches(dateRegexStrict.toRegex()) || lines[i + 1].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 1 < lines.size)
                    ) {
                        date = lines[i + 1].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    } else if ((lines[i + 2].matches(dateRegexStrict.toRegex()) || lines[i + 2].matches(
                            dateRegexLoose.toRegex()
                        )) && (i + 2 < lines.size)
                    ) {
                        date = lines[i + 2].replace("[^\\d., ]".toRegex(), "")
                            .replace("[., ]+$".toRegex(), "").replace(",", ".")
                    }

                    val strictPattern = Pattern.compile(dateRegexStrict)
                    val matcher = strictPattern.matcher(date)

                    if (matcher.find()) {
                        dateToSend = matcher.group()
                    }
                }
            }
            return dateToSend
        }

        fun preProcessBitmap(bitmap: Bitmap): Bitmap {
            var pix = preparePix(bitmap)
            pix = AdaptiveMap.pixContrastNorm(pix)
            pix = Enhance.unsharpMasking(pix)
            pix = Binarize.otsuAdaptiveThreshold(pix)
            pix = rotateToCorrectSkew(pix)

            return WriteFile.writeBitmap(pix)
        }

        fun preparePix(bitmap: Bitmap): Pix {
            return Convert.convertTo8(
                ReadFile.readBitmap(
                    bitmap.copy(
                        Bitmap.Config.ARGB_8888, true
                    )
                )
            )
        }

        fun rotateToCorrectSkew(pix: Pix): Pix {
            val skewAngle = Skew.findSkew(pix)
            return Rotate.rotate(pix, skewAngle)
        }


        fun prepareTesseractData(context: Context, lang: String = "urd"): String {
            val tessDir = File(context.filesDir, "tesseract")
            val tessDataDir = File(tessDir, "tessdata")
            if (!tessDataDir.exists()) tessDataDir.mkdirs()

            // Split languages (e.g., "eng+urd" -> ["eng", "urd"])
            val languages = lang.split("+")

            for (l in languages) {
                val dataFile = File(tessDataDir, "$l.traineddata")
                if (!dataFile.exists()) {
                    try {
                        context.assets.open("tessdata/$l.traineddata").use { input ->
                            FileOutputStream(dataFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return tessDir.absolutePath
        }



    }

}
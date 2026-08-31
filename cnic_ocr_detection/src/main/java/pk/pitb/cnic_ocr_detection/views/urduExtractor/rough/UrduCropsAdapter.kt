package pk.pitb.cnic_ocr_detection.views.urduExtractor.rough

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import pk.pitb.cnic_ocr_detection.R
import pk.pitb.cnic_ocr_detection.views.urduExtractor.CroppedUrduField
import pk.pitb.cnic_ocr_detection.views.urduExtractor.UrduExtractionResult

class UrduCropsAdapter(
    private val cropsList: List<CroppedUrduField>
) : RecyclerView.Adapter<UrduCropsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFieldName: TextView = view.findViewById(R.id.tvFieldName)
        val ivCroppedImage: ImageView = view.findViewById(R.id.ivCroppedImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_urdu_crop, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = cropsList[position]
        holder.tvFieldName.text = item.fieldName
        holder.ivCroppedImage.setImageBitmap(item.croppedBitmap)
    }

    override fun getItemCount(): Int = cropsList.size
}

fun showUrduCropsDialog(context: Context, result: UrduExtractionResult, extractedData: String) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_urdu_crops, null)

    val ivFullAnnotatedCnic: ImageView = dialogView.findViewById(R.id.ivFullAnnotatedCnic)
    val rvCrops: RecyclerView = dialogView.findViewById(R.id.rvCrops)
    val tvExtractedText: TextView = dialogView.findViewById(R.id.tvExtractedText)

    ivFullAnnotatedCnic.setImageBitmap(result.annotatedFullImage)
    tvExtractedText.text = extractedData

    rvCrops.layoutManager = LinearLayoutManager(context)
    rvCrops.adapter = UrduCropsAdapter(result.cropsUrdu)

    AlertDialog.Builder(context)
        .setTitle("Cropped Urdu Regions")
        .setView(dialogView)
        .setPositiveButton("Done") { dialog, _ ->
            dialog.dismiss()
        }
        .create()
        .show()
}
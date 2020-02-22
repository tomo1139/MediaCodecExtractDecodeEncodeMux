package develop.tomo1139.mediacodecextractdecodeencodemux

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.databinding.DataBindingUtil
import develop.tomo1139.mediacodecextractdecodeencodemux.databinding.ActivityMainBinding
import develop.tomo1139.mediacodecextractdecodeencodemux.media.ExtractDecodeEncodeMuxer
import develop.tomo1139.mediacodecextractdecodeencodemux.util.FilePickerUtil
import develop.tomo1139.mediacodecextractdecodeencodemux.util.Logger
import java.lang.Exception

class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy {
        DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding.button.setOnClickListener {
            showFileSelectDialog()
        }
    }

    private fun showFileSelectDialog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Logger.e("need permission")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_PERMISSION)
                }
            }
        } else {
            FilePickerUtil.showGallery(this, REQUEST_CODE_FILE_SELECT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.first() == PackageManager.PERMISSION_GRANTED) {
                FilePickerUtil.showGallery(this, REQUEST_CODE_FILE_SELECT)
            } else {
                Logger.e("need permission")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return

        if (requestCode == REQUEST_CODE_FILE_SELECT) {
            data?.data ?: return

            val inputFilePath = FilePickerUtil.getPath(this, data.data) ?: return
            val outputFilePath = getExternalFilesDir(null)?.absolutePath + "/output.mp4"
            Logger.e("input file path: $inputFilePath, outputFilePath: $outputFilePath")

            Thread {
                ExtractDecodeEncodeMuxer(inputFilePath, outputFilePath).doExtractDecodeEncodeMux()
                /*
                try {
                    ExtractDecodeEncodeMuxer(inputFilePath, outputFilePath).doExtractDecodeEncodeMux()
                } catch (e: Exception) {
                    Logger.e("e: $e")
                }
                */
                runOnUiThread {
                    Logger.e("Completed!! outputFilePath: $outputFilePath")
                    Toast.makeText(this, "Completed!!", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSION = 100
        private const val REQUEST_CODE_FILE_SELECT = 101
    }
}

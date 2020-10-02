package com.newlogic.mlkitlib.newlogic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.newlogic.mlkitlib.R
import com.newlogic.mlkitlib.innovatrics.barcode.BarcodeResult
import com.newlogic.mlkitlib.newlogic.extension.cacheImageToLocal
import com.newlogic.mlkitlib.newlogic.extension.getConnectionType
import com.newlogic.mlkitlib.newlogic.extension.toBitmap
import com.newlogic.mlkitlib.newlogic.utils.*
import com.newlogic.mlkitlib.newlogic.utils.Modes.BARCODE
import com.newlogic.mlkitlib.newlogic.utils.Modes.PDF_417
import kotlinx.android.synthetic.main.activity_mrz.*
import kotlinx.android.synthetic.main.activity_mrz.view.*
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MLKitActivity : AppCompatActivity(), View.OnClickListener {

    companion object {
        const val TAG = "MLKitActivity"
        const val MLKIT_RESULT = "MLKitResult"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        private lateinit var modelLayoutView: View
        private lateinit var CoordinatorLayoutView: View
        private lateinit var context: Context

        private var mode: String? = null
        private var rectangle: View? = null

        private object UIState {
            var mlkit: Boolean? = false
            var debug: Boolean? = false
        }
    }

    private var x = 0f
    private var y = 0f
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var flashButton: View? = null
    private var startScanTime: Long = 0
    private var tessStartScanTime: Long = 0
    private var onAnalyzerResult: (AnalyzerType, String) -> Unit = { a, b -> getAnalyzerResult(a, b) }
    private var onAnalyzerStat: (AnalyzerType, Long, Long) -> Unit = { a, b: Long, c: Long -> getAnalyzerStat(a, b, c) }

    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    private val clickThreshold = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mrz)
        modelLayoutView = findViewById(R.id.viewLayout)
        CoordinatorLayoutView =  findViewById(R.id.CoordinatorLayout)
        flashButton = findViewById(R.id.flash_button)
        rectangle = findViewById(R.id.rectimage)
        findViewById<View>(R.id.close_button).setOnClickListener(this)
        flashButton?.setOnClickListener(this)
        context = applicationContext
        val intent = intent
        mode = intent.getStringExtra("mode")
        when (mode) {
            Modes.MRZ.value -> { mlkitCheckbox.isChecked = true }
            PDF_417.value  -> { }
            BARCODE.value -> { }
        }
        UIState.mlkit = mlkitCheckbox.isChecked
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // TODO: This will not work if the file is updated in the APK, need to handle versioning
        if (!FileUtils.tesseractPathExists(this)) {
            if (FileUtils.createTesseractSubDir(this)) {
                FileUtils.copyFilesToSdCard(this)
            } else {
                //Timber.e(this.getClass().getSimpleName(), "Unknown file error. Cannot create subdirectory tessdata");
                Log.e(TAG, "Unknown file error. Cannot create subdirectory tessdata")
            }
        }

        val extDirPath: String = getExternalFilesDir(null)!!.absolutePath
        Log.d(TAG, "path: $extDirPath")
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                val snackBar: Snackbar = Snackbar.make(
                    CoordinatorLayoutView,
                    R.string.required_perms_not_given, Snackbar.LENGTH_INDEFINITE
                )
                snackBar.setAction(R.string.settings) { openSettingsApp() }
                snackBar.show()
            }
        }
    }

    private fun openSettingsApp() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }

    private fun startCamera() {

        this.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder().build()

            var size = Size(480, 640)
            if (mode == PDF_417.value) size = Size(1080, 1920)
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, getMrzAnalyzer())
                }

            // Select back camera
            val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()
            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
                preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())
                Log.d(TAG, "Measured size: ${viewFinder.width}x${viewFinder.height}")

                startScanTime = System.currentTimeMillis()
                tessStartScanTime = startScanTime
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun getAnalyzerResult(analyzerType: AnalyzerType, result: String): Unit {
        runOnUiThread {
            when (analyzerType) {
                AnalyzerType.MLKIT -> {
                    Log.d(TAG, "Success from MLKit")
                    mlkitCheckbox.isChecked = false
                    onMlkitCheckboxClicked(mlkitCheckbox)
                    mlkitText.text = result
                }
                AnalyzerType.BARCODE -> {
                    Log.d(TAG, "Success from Barcode")
                    Log.d(TAG, "value: $result")
                }
            }
            val data = Intent()
            data.putExtra(MLKIT_RESULT, result)
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    private fun getAnalyzerStat(analyzerType: AnalyzerType, startTime: Long, endTime: Long): Unit {
        runOnUiThread {
            val analyzerTime = endTime - startTime
            if (analyzerType == AnalyzerType.MLKIT) {
                mlkitMS.text = "Frame processing time: $analyzerTime ms"
                val scanTime = ((System.currentTimeMillis().toDouble() - startScanTime.toDouble()) / 1000)
                mlkitTime.text = "Total scan time: $scanTime s"
            }
        }
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    private fun getMrzAnalyzer(): MLKitAnalyzer {
        var barcodeBusy = false
        var mlkitBusy = false

        return MLKitAnalyzer { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val rot = imageProxy.imageInfo.rotationDegrees
                val bf = mediaImage.toBitmap(rot)
                val b = if (rot == 90 || rot == 270) Bitmap.createBitmap(bf, bf.width / 2, 0, bf.width / 2, bf.height)
                else Bitmap.createBitmap(bf, 0, bf.height / 2, bf.width, bf.height / 2)
                Log.d(TAG, "Bitmap: (${mediaImage.width}, ${mediaImage.height}) Cropped: (${b.width}, ${b.height}), Rotation: ${imageProxy.imageInfo.rotationDegrees}"
                )

                //barcode and pdf417
                if (!barcodeBusy && ((mode == PDF_417.value) || (mode == BARCODE.value))) {
                    barcodeBusy = true
                    Log.d("$TAG/MLKit", "barcode: mode is $mode")
                    val start = System.currentTimeMillis()
                    var options: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                            .build()
                    when (mode) {
                        PDF_417.value -> {
                            options = BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_PDF417)
                                    .build()
                        }
                        BARCODE.value -> {
                            options = BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(
                                            Barcode.FORMAT_CODE_128,
                                            Barcode.FORMAT_CODE_39,
                                            Barcode.FORMAT_CODE_93,
                                            Barcode.FORMAT_CODABAR,
                                            Barcode.FORMAT_DATA_MATRIX,
                                            Barcode.FORMAT_EAN_13,
                                            Barcode.FORMAT_EAN_8,
                                            Barcode.FORMAT_ITF,
                                            Barcode.FORMAT_QR_CODE,
                                            Barcode.FORMAT_UPC_A,
                                            Barcode.FORMAT_UPC_E,
                                            Barcode.FORMAT_AZTEC
                                    )
                                    .build()
                        }
                    }
                    val image = InputImage.fromBitmap(bf, imageProxy.imageInfo.rotationDegrees)
                    val scanner = BarcodeScanning.getClient(options)
                    Log.d("$TAG/MLKit", "barcode: process")
                    scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val timeRequired = System.currentTimeMillis() - start
                                val rawValue: String
                                val cornersString: String
                                Log.d("$TAG/MLKit", "barcode: success: $timeRequired ms")
                                if (barcodes.isNotEmpty()) {
                                    //                                val bounds = barcode.boundingBox
                                    val corners = barcodes[0].cornerPoints
                                    val builder = StringBuilder()
                                    if (corners != null) {
                                        for (corner in corners) {
                                            builder.append("${corner.x},${corner.y} ")
                                        }
                                    }
                                    cornersString = builder.toString()
                                    rawValue = barcodes[0].rawValue!!
                                    //                                val valueType = barcode.valueType
                                    val date = Calendar.getInstance().time
                                    val formatter = SimpleDateFormat("yyyyMMddHHmmss")
                                    val currentDateTime = formatter.format(date)
                                    val imageCachePathFile = "${context.cacheDir}/Scanner-$currentDateTime.jpg"
                                    bf.cacheImageToLocal(
                                            imageCachePathFile,
                                            imageProxy.imageInfo.rotationDegrees
                                    )
                                    val gson = Gson()
                                    val jsonString = gson.toJson(BarcodeResult(
                                            imageCachePathFile,
                                            cornersString,
                                            rawValue))
                                    onAnalyzerResult.invoke(AnalyzerType.BARCODE, jsonString)
                                } else {
                                    Log.d("$TAG/MLKit", "barcode: nothing detected")
                                }
                                barcodeBusy = false
                            }
                            .addOnFailureListener { e ->
                                Log.d("$TAG/MLKit", "barcode: failure: ${e.message}")
                                barcodeBusy = false
                            }
                }
                //MRZ
                if (UIState.mlkit!! && !mlkitBusy) {
                    mlkitBusy = true
                    val mlStartTime = System.currentTimeMillis()
                    val image = InputImage.fromBitmap(b, imageProxy.imageInfo.rotationDegrees)

                    // Pass image to an ML Kit Vision API
                    val recognizer = TextRecognition.getClient()
                    Log.d("$TAG/MLKit", "TextRecognition: process")
                    val start = System.currentTimeMillis()
                    recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                modelLayoutView.modelText.visibility = View.INVISIBLE
                                val timeRequired = System.currentTimeMillis() - start

                                Log.d("$TAG/MLKit", "TextRecognition: success: $timeRequired ms")
                                var rawFullRead = ""
                                val blocks = visionText.textBlocks
                                for (i in blocks.indices) {
                                    val lines = blocks[i].lines
                                    for (j in lines.indices) {
                                        if (lines[j].text.contains('<')) {
                                            rawFullRead += lines[j].text + "\n"
                                        }
                                    }
                                }
                                rectangle!!.isSelected = rawFullRead != ""

                                try {
                                    Log.d(
                                            "$TAG/MLKit",
                                            "Before cleaner: [${
                                                URLEncoder.encode(rawFullRead, "UTF-8")
                                                        .replace("%3C", "<").replace("%0A", "↩")
                                            }]"
                                    )
                                    val mrz = MRZCleaner.clean(rawFullRead)
                                    Log.d(
                                            "$TAG/MLKit",
                                            "After cleaner = [${
                                                URLEncoder.encode(mrz, "UTF-8")
                                                        .replace("%3C", "<").replace("%0A", "↩")
                                            }]"
                                    )
                                    val record = MRZCleaner.parseAndClean(mrz)
                                    val date = Calendar.getInstance().time
                                    val formatter = SimpleDateFormat("yyyyMMddHHmmss")
                                    val currentDateTime = formatter.format(date)
                                    val imageCachePathFile = "${context.cacheDir}/Scanner-$currentDateTime.jpg"
                                    bf.cacheImageToLocal(imageCachePathFile, imageProxy.imageInfo.rotationDegrees)

                                    // record to json
                                    val gson = Gson()
                                    val jsonString = gson.toJson(MRZResult(
                                            imageCachePathFile,
                                            record.code.toString(),
                                            record.code1.toShort(),
                                            record.code2.toShort(),
                                            record.dateOfBirth?.toString()?.replace(Regex("[{}]"), ""),
                                            record.documentNumber.toString(),
                                            record.expirationDate?.toString()?.replace(Regex("[{}]"), ""),
                                            record.format.toString(),
                                            record.givenNames,
                                            record.issuingCountry,
                                            record.nationality,
                                            record.sex.toString(),
                                            record.surname,
                                            record.toMrz()
                                    ))
                                    onAnalyzerResult.invoke(AnalyzerType.MLKIT, jsonString)
                                } catch (e: Exception) { // MrzParseException, IllegalArgumentException
                                    Log.d("$TAG/MLKit", e.toString())
                                }
                                onAnalyzerStat.invoke(
                                        AnalyzerType.MLKIT,
                                        mlStartTime,
                                        System.currentTimeMillis()
                                )
                                mlkitBusy = false
                            }
                            .addOnFailureListener { e ->
                                Log.d("$TAG/MLKit", "TextRecognition: failure: ${e.message}")
                                if (getConnectionType() == 0) {
                                    modelLayoutView.modelText.text = context.getString(R.string.connection_text)
                                } else {
                                    modelLayoutView.modelText.text = context.getString(R.string.model_text)
                                }
                                modelLayoutView.modelText.visibility = View.VISIBLE
                                onAnalyzerStat.invoke(
                                        AnalyzerType.MLKIT,
                                        mlStartTime,
                                        System.currentTimeMillis()
                                )
                                mlkitBusy = false
                            }
                }
            }
            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let { File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    fun onMlkitCheckboxClicked(view: View) {
        if (view is Switch) {
            val checked: Boolean = view.isChecked
            UIState.mlkit = checked
            Log.d(TAG, "UIState.mlkit: ${UIState.mlkit}")
            if (checked) {
                startScanTime = System.currentTimeMillis()
                mlkitText.text = ""
                mlkitMS.text = ""
                mlkitTime.text = ""
            }
        }
    }

    override fun onClick(view: View) {
        val id = view.id
        Log.d(TAG, "view: $id")
        if (id == R.id.close_button) {
            onBackPressed()
        } else if (id == R.id.flash_button) {
            if (flashButton!!.isSelected) {
                flashButton!!.isSelected = false
                camera?.cameraControl?.enableTorch(false)
            } else {
                flashButton!!.isSelected = true
                camera?.cameraControl?.enableTorch(true)
            }
        }
    }

    private fun isAClick(
        startX: Float,
        endX: Float,
        startY: Float,
        endY: Float
    ): Boolean {
        val differenceX = abs(startX - endX)
        val differenceY = abs(startY - endY)
        return !(differenceX > clickThreshold || differenceY > clickThreshold)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val minDistance = 600
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                y = event.y
                x = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (isAClick(x, event.x, y, event.y)) {
                    val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                        viewFinder.width.toFloat(), viewFinder.height.toFloat()
                    )
                    val autoFocusPoint = factory.createPoint(event.x, event.y)
                    try {
                        camera!!.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                autoFocusPoint,
                                FocusMeteringAction.FLAG_AF
                            ).apply {
                                //focus only when the user tap the preview
                                disableAutoCancel()
                            }.build()
                        )
                    } catch (e: CameraInfoUnavailableException) {
                        Log.d("ERROR", "cannot access camera", e)
                    }
                } else {
                    val deltaY = event.y - y
                    if (deltaY < -minDistance) {
                        // Toast.makeText(this, "bottom2up swipe: $y1, $y2 -> $deltaY", Toast.LENGTH_SHORT).show()
                        debugLayout.visibility = View.VISIBLE
                    } else if (deltaY > minDistance) {
                        debugLayout.visibility = View.INVISIBLE
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
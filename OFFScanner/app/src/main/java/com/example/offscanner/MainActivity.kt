package com.example.offscanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
	private lateinit var previewView: PreviewView
	private lateinit var outputText: TextView

	private val cameraExecutor = Executors.newSingleThreadExecutor()
	private val uiScope = CoroutineScope(Dispatchers.Main)
	private val ioScope = CoroutineScope(Dispatchers.IO)

	private var lastScannedCode: String? = null
	private var debounceJob: Job? = null

	private val client: OkHttpClient by lazy {
		OkHttpClient.Builder()
			.callTimeout(10, TimeUnit.SECONDS)
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.build()
	}

	private val permissionLauncher =
		registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			if (granted) startCamera() else outputText.text = "Camera permission denied"
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		previewView = findViewById(R.id.previewView)
		outputText = findViewById(R.id.outputText)

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera()
		} else {
			permissionLauncher.launch(Manifest.permission.CAMERA)
		}
	}

	private fun startCamera() {
		val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
		cameraProviderFuture.addListener({
			val cameraProvider = cameraProviderFuture.get()

			val preview = Preview.Builder()
				.setTargetResolution(Size(1280, 720))
				.build()
			preview.setSurfaceProvider(previewView.surfaceProvider)

			val analysis = ImageAnalysis.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.setTargetResolution(Size(1280, 720))
				.build()
			analysis.setAnalyzer(cameraExecutor) { imageProxy ->
				scanBarcodes(imageProxy)
			}

			val selector = CameraSelector.DEFAULT_BACK_CAMERA
			try {
				cameraProvider.unbindAll()
				cameraProvider.bindToLifecycle(this, selector, preview, analysis)
			} catch (e: Exception) {
				outputText.text = "Camera error: ${e.message}"
			}
		}, ContextCompat.getMainExecutor(this))
	}

	private fun scanBarcodes(imageProxy: ImageProxy) {
		val mediaImage = imageProxy.image
		if (mediaImage != null) {
			val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
			val scanner = BarcodeScanning.getClient()
			scanner.process(image)
				.addOnSuccessListener { barcodes ->
					if (barcodes.isNotEmpty()) {
						val firstCode = barcodes.firstOrNull { it.format == Barcode.FORMAT_EAN_13 || it.format == Barcode.FORMAT_EAN_8 || it.format == Barcode.FORMAT_UPC_A || it.format == Barcode.FORMAT_UPC_E }
						val value = firstCode?.rawValue
						if (!value.isNullOrBlank() && value != lastScannedCode) {
							lastScannedCode = value
							debounceFetch(value)
						}
					}
				}
				.addOnFailureListener { }
				.addOnCompleteListener { imageProxy.close() }
		} else {
			imageProxy.close()
		}
	}

	private fun debounceFetch(barcode: String) {
		debounceJob?.cancel()
		debounceJob = uiScope.launch {
			outputText.text = "Scanned: $barcode\nFetching product..."
			fetchProduct(barcode)
		}
	}

	private fun fetchProduct(barcode: String) {
		ioScope.launch {
			val cached = getCache(this@MainActivity, barcode)
			if (cached != null) {
				uiScope.launch { outputText.text = cached }
				return@launch
			}

			val url = "https://world.openfoodfacts.org/api/v0/product/${barcode}.json"
			val request = Request.Builder()
				.url(url)
				.header("Accept", "application/json")
				.header("User-Agent", "OFFScanner/1.0 (github.com/example/offscanner) -- contact@example.com")
				.build()
			try {
				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						uiScope.launch { outputText.text = "Fetch error: ${response.code}" }
						return@use
					}
					val body = response.body?.string() ?: "{}"
					val json = JSONObject(body)
					if (json.optInt("status") == 1) {
						val product = json.getJSONObject("product")
						val name = product.optString("product_name", product.optString("generic_name", "Unknown"))
						val ingredients = product.optString("ingredients_text_en", product.optString("ingredients_text", "(no ingredients field)"))
						val display = "Product: ${name}\nIngredients:\n${ingredients}"
						saveCache(this@MainActivity, barcode, display)
						uiScope.launch { outputText.text = display }
					} else {
						uiScope.launch { outputText.text = "Product not found in Open Food Facts." }
					}
				}
			} catch (e: Exception) {
				uiScope.launch { outputText.text = "Fetch error: ${e.message}" }
			}
		}
	}

	private fun getCache(context: Context, key: String): String? {
		val prefs = context.getSharedPreferences("off_cache", Context.MODE_PRIVATE)
		return prefs.getString(key, null)
	}

	private fun saveCache(context: Context, key: String, value: String) {
		val prefs = context.getSharedPreferences("off_cache", Context.MODE_PRIVATE)
		prefs.edit().putString(key, value).apply()
	}
}
package com.example.lumipos.ui.pos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.example.lumipos.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class BarcodeScannerFragment : DialogFragment() {

    private val executor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }

    @Volatile
    private var handled = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                publishResult(null)
                dismissAllowingStateLoss()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_barcode_scanner, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btn_close_scanner)
            .setOnClickListener { dismissAllowingStateLoss() }

        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startCamera() {
        val previewView = view?.findViewById<PreviewView>(R.id.preview_view) ?: return
        val providerFuture = ProcessCameraProvider.getInstance(requireContext())
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy -> analyze(imageProxy) }
            provider.unbindAll()
            provider.bindToLifecycle(
                viewLifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || handled) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (handled) return@addOnSuccessListener
                val value = barcodes.firstNotNullOfOrNull { barcode -> barcode.rawValue }
                if (value != null) {
                    handled = true
                    publishResult(value)
                    dismissAllowingStateLoss()
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun publishResult(barcode: String?) {
        val result = Bundle().apply { putString(RESULT_BARCODE, barcode) }
        parentFragmentManager.setFragmentResult(REQUEST_BARCODE, result)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdown()
        scanner.close()
    }

    companion object {
        const val REQUEST_BARCODE = "barcode_scan_request"
        const val RESULT_BARCODE = "barcode_scan_result"
    }
}

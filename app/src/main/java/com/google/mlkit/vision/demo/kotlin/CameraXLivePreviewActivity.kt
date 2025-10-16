/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.vision.demo.kotlin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.common.annotation.KeepName
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.demo.CameraXViewModel
import com.google.mlkit.vision.demo.GraphicOverlay
import com.google.mlkit.vision.demo.R
import com.google.mlkit.vision.demo.VisionImageProcessor
import com.google.mlkit.vision.demo.kotlin.objectdetector.ObjectDetectorProcessor
import com.google.mlkit.vision.demo.preference.PreferenceUtils
import com.google.mlkit.vision.demo.preference.SettingsActivity
import com.google.mlkit.vision.demo.preference.SettingsActivity.LaunchSource

@KeepName
class CameraXLivePreviewActivity :
  AppCompatActivity(), CompoundButton.OnCheckedChangeListener {

  private var previewView: PreviewView? = null
  private var graphicOverlay: GraphicOverlay? = null
  private var cameraProvider: ProcessCameraProvider? = null
  private var camera: Camera? = null
  private var previewUseCase: Preview? = null
  private var analysisUseCase: ImageAnalysis? = null
  private var imageProcessor: VisionImageProcessor? = null
  private var needUpdateGraphicOverlayImageSourceInfo = false
  private var lensFacing = CameraSelector.LENS_FACING_BACK
  private var cameraSelector: CameraSelector? = null

  private val CUSTOM_MODEL_PATH = "model.tflite"

  // permissão da câmera
  private val requestPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
      if (isGranted) {
        startCameraProviderObserver()
      } else {
        Toast.makeText(this, "Permissão da câmera é necessária para continuar.", Toast.LENGTH_LONG).show()
        finish()
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate")

    cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
    setContentView(R.layout.activity_vision_camerax_live_preview)
    previewView = findViewById(R.id.preview_view)
    if (previewView == null) {
      Log.d(TAG, "previewView is null")
    }
    graphicOverlay = findViewById(R.id.graphic_overlay)
    if (graphicOverlay == null) {
      Log.d(TAG, "graphicOverlay is null")
    }

    val facingSwitch = findViewById<ToggleButton>(R.id.facing_switch)
    facingSwitch.setOnCheckedChangeListener(this)

    val settingsButton = findViewById<ImageView>(R.id.settings_button)
    settingsButton.setOnClickListener {
      val intent = Intent(applicationContext, SettingsActivity::class.java)
      intent.putExtra(SettingsActivity.EXTRA_LAUNCH_SOURCE, LaunchSource.CAMERAX_LIVE_PREVIEW)
      startActivity(intent)
    }
  }

  override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
    if (cameraProvider == null) {
      return
    }
    val newLensFacing =
      if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        CameraSelector.LENS_FACING_BACK
      } else {
        CameraSelector.LENS_FACING_FRONT
      }
    val newCameraSelector = CameraSelector.Builder().requireLensFacing(newLensFacing).build()
    try {
      if (cameraProvider!!.hasCamera(newCameraSelector)) {
        Log.d(TAG, "Set facing to $newLensFacing")
        lensFacing = newLensFacing
        cameraSelector = newCameraSelector
        bindAllCameraUseCases()
        return
      }
    } catch (e: CameraInfoUnavailableException) {
      // Falls through
    }
    Toast.makeText(
      applicationContext,
      "Este dispositivo não tem uma lente com facing: $newLensFacing",
      Toast.LENGTH_SHORT
    ).show()
  }

  public override fun onResume() {
    super.onResume()
    checkCameraPermission()
  }

  override fun onPause() {
    super.onPause()
    imageProcessor?.run { this.stop() }
  }

  public override fun onDestroy() {
    super.onDestroy()
    imageProcessor?.run { this.stop() }
  }

  private fun checkCameraPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      startCameraProviderObserver()
    } else {
      requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  private fun startCameraProviderObserver() {
    ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
      .get(CameraXViewModel::class.java)
      .processCameraProvider
      .observe(
        this,
        Observer { provider: ProcessCameraProvider? ->
          cameraProvider = provider
          bindAllCameraUseCases()
        }
      )
  }

  private fun bindAllCameraUseCases() {
    if (cameraProvider == null) return

    cameraProvider!!.unbindAll()

    // Preview
    val previewBuilder = Preview.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      previewBuilder.setTargetResolution(targetResolution)
    }
    previewUseCase = previewBuilder.build()
    previewUseCase!!.setSurfaceProvider(previewView!!.surfaceProvider)

    // Image Analysis
    imageProcessor?.run { this.stop() }
    imageProcessor =
      try {
        Log.i(TAG, "Using Custom Object Detector Processor with model: $CUSTOM_MODEL_PATH")
        val localModel = LocalModel.Builder().setAssetFilePath(CUSTOM_MODEL_PATH).build()
        val customObjectDetectorOptions =
          PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
        ObjectDetectorProcessor(this, customObjectDetectorOptions)
      } catch (e: Exception) {
        Log.e(TAG, "Can not create image processor for custom object detection", e)
        Toast.makeText(
          applicationContext,
          "Can not create image processor: " + e.localizedMessage,
          Toast.LENGTH_LONG
        ).show()
        return
      }

    val analysisBuilder = ImageAnalysis.Builder()
    if (targetResolution != null) {
      analysisBuilder.setTargetResolution(targetResolution)
    }
    analysisUseCase = analysisBuilder.build()

    needUpdateGraphicOverlayImageSourceInfo = true
    analysisUseCase!!.setAnalyzer(
      ContextCompat.getMainExecutor(this),
      ImageAnalysis.Analyzer { imageProxy ->
        if (needUpdateGraphicOverlayImageSourceInfo) {
          val rotationDegrees = imageProxy.imageInfo.rotationDegrees
          if (rotationDegrees == 0 || rotationDegrees == 180) {
            graphicOverlay!!.setImageSourceInfo(
              imageProxy.width,
              imageProxy.height,
              lensFacing == CameraSelector.LENS_FACING_FRONT
            )
          } else {
            graphicOverlay!!.setImageSourceInfo(
              imageProxy.height,
              imageProxy.width,
              lensFacing == CameraSelector.LENS_FACING_FRONT
            )
          }
          needUpdateGraphicOverlayImageSourceInfo = false
        }
        try {
          imageProcessor!!.processImageProxy(imageProxy, graphicOverlay)
        } catch (e: MlKitException) {
          Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
          Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT).show()
        }
      }
    )

    camera = cameraProvider!!.bindToLifecycle(
      this, cameraSelector!!, previewUseCase!!, analysisUseCase!!
    )
  }

  private fun bindPreviewUseCase() {
    if (!PreferenceUtils.isCameraLiveViewportEnabled(this)) {
      return
    }
    if (cameraProvider == null) {
      return
    }
    if (previewUseCase != null) {
      cameraProvider!!.unbind(previewUseCase)
    }

    val builder = Preview.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    previewUseCase = builder.build()
    previewUseCase!!.setSurfaceProvider(previewView!!.surfaceProvider)
  }

  private fun bindAnalysisUseCase() {
    if (cameraProvider == null) {
      return
    }
    if (analysisUseCase != null) {
      cameraProvider!!.unbind(analysisUseCase)
    }
    if (imageProcessor != null) {
      imageProcessor!!.stop()
    }
    imageProcessor =
      try {
        Log.i(TAG, "Using Custom Object Detector Processor with model: $CUSTOM_MODEL_PATH")
        val localModel = LocalModel.Builder().setAssetFilePath(CUSTOM_MODEL_PATH).build()
        val customObjectDetectorOptions =
          PreferenceUtils.getCustomObjectDetectorOptionsForLivePreview(this, localModel)
        ObjectDetectorProcessor(this, customObjectDetectorOptions)
      } catch (e: Exception) {
        Log.e(TAG, "Can not create image processor for custom object detection", e)
        Toast.makeText(
          applicationContext,
          "Can not create image processor: " + e.localizedMessage,
          Toast.LENGTH_LONG
        ).show()
        return
      }

    val builder = ImageAnalysis.Builder()
    val targetResolution = PreferenceUtils.getCameraXTargetResolution(this, lensFacing)
    if (targetResolution != null) {
      builder.setTargetResolution(targetResolution)
    }
    analysisUseCase = builder.build()

    needUpdateGraphicOverlayImageSourceInfo = true
    analysisUseCase?.setAnalyzer(
      ContextCompat.getMainExecutor(this),
      ImageAnalysis.Analyzer { imageProxy ->
        if (needUpdateGraphicOverlayImageSourceInfo) {
          val rotationDegrees = imageProxy.imageInfo.rotationDegrees
          if (rotationDegrees == 0 || rotationDegrees == 180) {
            graphicOverlay!!.setImageSourceInfo(imageProxy.width, imageProxy.height, lensFacing == CameraSelector.LENS_FACING_FRONT)
          } else {
            graphicOverlay!!.setImageSourceInfo(imageProxy.height, imageProxy.width, lensFacing == CameraSelector.LENS_FACING_FRONT)
          }
          needUpdateGraphicOverlayImageSourceInfo = false
        }
        try {
          imageProcessor!!.processImageProxy(imageProxy, graphicOverlay)
        } catch (e: MlKitException) {
          Log.e(TAG, "Failed to process image. Error: " + e.localizedMessage)
          Toast.makeText(applicationContext, e.localizedMessage, Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  companion object {
    private const val TAG = "CameraXLivePreview"
  }
}
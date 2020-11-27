package com.example.ziri

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.databinding.DataBindingUtil
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.rekognition.AmazonRekognition
import com.amazonaws.services.rekognition.AmazonRekognitionClient
import com.amazonaws.services.rekognition.model.FaceMatch
import com.amazonaws.services.rekognition.model.Image
import com.amazonaws.services.rekognition.model.SearchFacesByImageRequest
import com.amazonaws.services.rekognition.model.SearchFacesByImageResult
import com.example.ziri.BitmapUtils.imageToMat
import com.example.ziri.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.mv.engine.FaceBox
import com.mv.engine.Live
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.nio.ByteBuffer


class FaceAnalyzer(val context: Context) : ImageAnalysis.Analyzer {
    private val activity = context as Activity

    private val detectionContext = newSingleThreadContext("detection")
    private val detector: FaceDetector
    private var live: Live = Live()
    private var rekognitionClient: AmazonRekognition

    //aws variables
    var collectionId = "staff"
    var access_key = "AKIA3UPJI2TGVCSMQ6XZ"
    var secret_key = "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"

    private var threshold: Float = defaultThreshold

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0


    private var factorX: Float = 0F
    private var factorY: Float = 0F

    private val previewWidth: Int = 640
    private val previewHeight: Int = 480

    private val frameOrientation: Int = 7

    private var prevId = -1

    private lateinit var binding: ActivityMainBinding

    private lateinit var scaleAnimator: ObjectAnimator


    init {

        Log.d(tag, "INITIALIZNG >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
        binding = DataBindingUtil.setContentView(activity, R.layout.activity_main)
        binding.result = DetectionResult()
        calculateSize()


        // Real-time contour detection
        val options = FaceDetectorOptions.Builder()
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .setMinFaceSize(0.3f)
            .build()

        detector = FaceDetection.getClient(options)
        live.loadModel(context.assets)


        val credentials: AWSCredentials = BasicAWSCredentials(access_key, secret_key)
        rekognitionClient = AmazonRekognitionClient(credentials)
        rekognitionClient.setRegion(Region.getRegion(Regions.AP_SOUTHEAST_1))





        scaleAnimator = ObjectAnimator.ofFloat(binding.scan, View.SCALE_Y, 1F, -1F, 1F).apply {
            this.duration = 3000
            this.repeatCount = ValueAnimator.INFINITE
            this.repeatMode = ValueAnimator.REVERSE
            this.interpolator = LinearInterpolator()
            this.start()
        }


    }


    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        val rotation = imageProxy.imageInfo.rotationDegrees


        if (mediaImage != null) {

            factorX = screenWidth / imageProxy.height.toFloat()
            factorY = screenHeight / imageProxy.width.toFloat()

            val image = InputImage.fromMediaImage(mediaImage, rotation)
            val imageArray = imageToMat(mediaImage)
            val bitmapImage =
                BitmapUtils.rotateBitmap(BitmapUtils.toBitmap(mediaImage), rotation.toFloat())
            val result = detector.process(image)
                .addOnSuccessListener { faces ->
                    // Task completed successfully
                    // ...
                    Log.d(tag, "Faces size is " + faces.size)

                    if (faces.isNotEmpty()) {
                        Log.d(tag, "Inside not empty")
                        val face = faces[0]


                        val bounds = face.boundingBox
                        val faceBox =
                            FaceBox(bounds.left, bounds.top, bounds.right, bounds.bottom, 1.0f)


                        //    val bitmapImage: Bitmap =
                        //        BitmapUtils.rotateBitmap(BitmapUtils.toBitmap(mediaImage), rotation.toFloat())

                        GlobalScope.launch(detectionContext) {

                            val score = detectLive(
                                imageArray,
                                imageProxy.width,
                                imageProxy.height,
                                frameOrientation,
                                faceBox
                            )

                            Log.d(tag, "Confidence score is" + score)

                            var detectionResult = DetectionResult(faceBox, 0, true)
                            detectionResult.confidence = score
                            detectionResult.threshold = threshold
                            val rect = calculateBoxLocationOnScreen(
                                detectionResult.left,
                                detectionResult.top,
                                detectionResult.right,
                                detectionResult.bottom
                            )

                            binding.result = detectionResult.updateLocation(rect)
                            binding.rectView.postInvalidate()


                            if (detectionResult.confidence >= threshold) {
                                if (face.trackingId != prevId) {
                                    face.trackingId?.let { prevId = it }

                                    //prepare image for sending to  aws
                                    val croppedBitmap: Bitmap = BitmapUtils.cropBitmap(
                                        bitmapImage,
                                        face.boundingBox
                                    )

                                    if (croppedBitmap.getByteCount() < 1000000) {
                                       val aws_result =  fetchAwsData(croppedBitmap)

                                        Log.d(tag,"AWS RESULT is " + aws_result)
                                    }



                                } else {
                                    //same face
                                }
                            }

                        }

                    }
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    // Task failed with an exception
                    // ...
                }

        }

    }



     fun detectLive(
        imageArray: ByteArray,
        width: Int,
        height: Int,
        frameOrientation: Int,
        faceBox: FaceBox
    ): Float = live.detect(
        imageArray, width,
        height,
        frameOrientation, faceBox
    )


     fun fetchAwsData(photo: Bitmap): String {
        var result = "error"

        var searchFacesByImageRequest: SearchFacesByImageRequest
        var searchFacesByImageResult: SearchFacesByImageResult

        val imageBytes = ByteBuffer.wrap(
            Base64.decode(
                BitmapUtils.bitmapToBase64(photo),
                Base64.DEFAULT
            )
        )
        val awsimage: Image =
            com.amazonaws.services.rekognition.model.Image()
                .withBytes(imageBytes)

        // Search collection for faces similar to the largest face in the image.


        // Search collection for faces similar to the largest face in the image.
        searchFacesByImageRequest = SearchFacesByImageRequest()
            .withCollectionId(collectionId)
            .withImage(awsimage)
            .withFaceMatchThreshold(90f)
            .withMaxFaces(1)

        try {
            searchFacesByImageResult =
                rekognitionClient.searchFacesByImage(searchFacesByImageRequest)
            val faceImageMatches: List<FaceMatch> =
                searchFacesByImageResult.faceMatches
            Log.d("Amazon MATCH", "Face Matches" + faceImageMatches.size)
            val confidence = 0f
            if (!faceImageMatches.isEmpty()) {
                var bestMatch = faceImageMatches[0]
                var similarity = 0f
                for (match in faceImageMatches) {
                    if (similarity < match.similarity) {
                        bestMatch = match
                        similarity = match.similarity
                    }
                    Log.d("Amazon MATCH", match.toString())
                    result = bestMatch.face.externalImageId
                }
            }
        } catch (e: Exception) {
            Log.d("Amazon Error", e.toString())
        }

        return result

    }


    private fun calculateSize() {
        val dm = DisplayMetrics()


        activity.windowManager.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    private fun calculateBoxLocationOnScreen(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect(
            (left * factorX).toInt(),
            (top * factorY).toInt(),
            (right * factorX).toInt(),
            (bottom * factorY).toInt()
        )


    companion object {
        const val tag = "MainActivity"
        const val defaultThreshold = 0.915F

    }


}
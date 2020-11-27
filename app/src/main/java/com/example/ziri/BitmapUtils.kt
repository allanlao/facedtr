package com.example.ziri

import android.graphics.*
import android.media.Image
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object BitmapUtils {

    fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun toBitmap(image: Image): Bitmap {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        //U and V are swapped
        yBuffer[nv21, 0, ySize]
        vBuffer[nv21, ySize, vSize]
        uBuffer[nv21, ySize + vSize, uSize]
        val yuvImage =
            YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, yuvImage.width, yuvImage.height),
            75,
            out
        )
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    fun imageToMat(image: Image): ByteArray {
        val planes: Array<Image.Plane> = image.planes
        val buffer0: ByteBuffer = planes[0].buffer
        val buffer1: ByteBuffer = planes[1].buffer
        val buffer2: ByteBuffer = planes[2].buffer
        val offset = 0
        val width: Int = image.width
        val height: Int = image.height
        val data = ByteArray(
            image.width * image.height * ImageFormat.getBitsPerPixel(
                ImageFormat.YUV_420_888
            ) / 8
        )
        val rowData1 = ByteArray(planes[1].rowStride)
        val rowData2 = ByteArray(planes[2].rowStride)
        val bytesPerPixel =
            ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8

        // loop via rows of u/v channels
        var offsetY = 0
        val sizeY = width * height * bytesPerPixel
        val sizeUV = width * height * bytesPerPixel / 4
        for (row in 0 until height) {

            // fill data for Y channel, two row
            run {
                val length = bytesPerPixel * width
                buffer0[data, offsetY, length]
                if (height - row != 1) buffer0.position(
                    buffer0.position() + planes[0].rowStride - length
                )
                offsetY += length
            }
            if (row >= height / 2) continue
            run {
                var uvlength: Int = planes[1].rowStride
                if (height / 2 - row == 1) {
                    uvlength = width / 2 - planes[1].pixelStride + 1
                }
                buffer1[rowData1, 0, uvlength]
                buffer2[rowData2, 0, uvlength]

                // fill data for u/v channels
                for (col in 0 until width / 2) {
                    // u channel
                    data[sizeY + row * width / 2 + col] =
                        rowData1[col * planes[1].pixelStride]

                    // v channel
                    data[sizeY + sizeUV + row * width / 2 + col] =
                        rowData2[col * planes[2].pixelStride]
                }
            }
        }
        return data
    }


    fun cropBitmap(bitmap: Bitmap, rect: Rect): Bitmap {
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        val ret = Bitmap.createBitmap(w, h, bitmap.config)
        val canvas = Canvas(ret)
        canvas.drawBitmap(bitmap, -rect.left.toFloat(), -rect.top.toFloat(), null)
        return ret
    }




}
package com.example.androidlab.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface

object BitmapLoader {

    /**
     * URI에 있는 이미지를 Bitmap으로 가져온다.
     * EXIF 방향 정보도 확인해서 사진을 올바르게 회전시킨다.
     */
    fun load(
        context: Context,
        uri: android.net.Uri
    ): Bitmap {

        val resolver = context.contentResolver

        // 이미지 읽기
        val bitmap = resolver.openInputStream(uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: throw IllegalArgumentException("이미지를 읽을 수 없습니다.")

        // EXIF 방향 읽기
        val orientation = resolver.openInputStream(uri).use { inputStream ->
            if (inputStream != null) {
                ExifInterface(inputStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }
        }

        // 회전 필요 없으면 그대로 반환
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return bitmap
        }

        val matrix = Matrix()

        when (orientation) {

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }

            else -> {
                return bitmap
            }
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
}
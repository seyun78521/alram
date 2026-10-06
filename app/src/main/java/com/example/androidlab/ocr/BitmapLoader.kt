package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.InputStream

/*
 * 이미지 파일을 Bitmap으로 읽어오는 클래스
 *
 * 사진이 가로/세로가 뒤집혀 있으면
 * EXIF 정보를 보고 올바른 방향으로 돌린다.
 */
object BitmapLoader {

    fun decodeUpright(
        input: InputStream
    ): Bitmap {

        // 이미지 파일을 바이트로 읽는다.
        val bytes =
            input.readBytes()

        // Bitmap으로 변환
        val bitmap =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size
            )
                ?: throw IllegalArgumentException(
                    "이미지를 읽을 수 없습니다."
                )

        // 사진의 회전 정보 읽기
        val orientation =
            ExifInterface(
                ByteArrayInputStream(bytes)
            ).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

        val matrix =
            Matrix()

        when (orientation) {

            ExifInterface.ORIENTATION_ROTATE_90 ->
                matrix.postRotate(90f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                matrix.postRotate(180f)

            ExifInterface.ORIENTATION_ROTATE_270 ->
                matrix.postRotate(270f)

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                matrix.postScale(-1f, 1f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                matrix.postScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            // 이미 정상 방향
            ExifInterface.ORIENTATION_NORMAL -> {
                return bitmap
            }

            else -> {
                return bitmap
            }
        }

        // 실제 회전된 Bitmap 생성
        val rotated =
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

        // 기존 Bitmap 제거
        if (rotated != bitmap) {
            bitmap.recycle()
        }

        return rotated
    }
}
package com.example.androidlab.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.InputStream

// 사진 파일을 Bitmap(사진 데이터)으로 읽고, 똑바로 세워서 돌려주는 도구.
// 폰으로 찍은 사진은 "옆으로 누운 채" 저장되고 회전 정보(EXIF)만 따로 적혀 있는 경우가 많다.
// 이 정보를 무시하면 OCR이 글자 위치를 잘못 읽으므로, 여기서 먼저 돌려 놓는다.
object BitmapLoader {

    fun decodeUpright(input: InputStream): Bitmap {
        // 1) 파일 내용을 전부 바이트 배열로 읽는다. (같은 내용을 두 번 쓰려고 한 번에 읽어 둔다)
        val bytes = input.readBytes()

        // 2) 바이트를 Bitmap으로 바꾼다. 읽을 수 없는 파일이면 null이 나와서 예외를 던진다.
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("이미지를 읽을 수 없습니다.")

        // 3) 사진에 적힌 회전 정보를 읽는다. 없으면 "정상 방향"으로 본다.
        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        // 4) 회전 정보에 맞는 변환(Matrix)을 만든다. Matrix = 돌리기/뒤집기 규칙
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f) // 좌우 뒤집기
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)   // 상하 뒤집기
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap // 이미 똑바른 사진이면 그대로 돌려준다
        }

        // 5) 변환을 적용한 새 Bitmap을 만들고, 원래 것은 메모리에서 비운다.
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }
}
package me.ezar.anemon.utils

import java.io.File

object FileManager {
    val imagesFolder = File("images")

    init {
        imagesFolder.mkdirs()
    }

    fun ByteArray.saveAsImageFile(): File {
        val file = imagesFolder.resolve(System.currentTimeMillis().toString() + ".png")
        file.createNewFile()
        file.writeBytes(this)
        return file
    }

}
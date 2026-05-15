package org.jetbrains.bio.qfarm.output.tree

import org.jetbrains.bio.qfarm.OUTPUT
import java.io.File
import java.net.URI

fun relativeToTreeSvg(url: String): String {
    val file = try {
        File(URI(url)).canonicalFile
    } catch (_: Exception) {
        File(url).canonicalFile
    }

    return OUTPUT.runDir
        .canonicalFile
        .toPath()
        .relativize(file.toPath())
        .toString()
        .replace(File.separatorChar, '/')
}

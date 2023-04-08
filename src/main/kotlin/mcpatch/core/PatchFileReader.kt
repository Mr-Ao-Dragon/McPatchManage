package mcpatch.core

import mcpatch.data.ModificationMode
import mcpatch.data.NewFile
import mcpatch.data.VersionData
import mcpatch.exception.McPatchManagerException
import mcpatch.extension.StreamExtension.copyAmountTo
import mcpatch.stream.ActionedInputStream
import mcpatch.stream.EmptyInputStream
import mcpatch.stream.SHA1CheckInputStream
import mcpatch.utils.File2
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.tools.bzip2.CBZip2InputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class PatchFileReader(val version: String, val file: File2) : Iterable<PatchFileReader.PatchEntry>, AutoCloseable
{
    val archive = ZipFile(file.file, "utf-8")
    val meta: VersionData

    init {
        // 读取元数据
        val metaEntry = archive.getEntry(".mcpatch-meta.json") ?: throw McPatchManagerException("找不到更新包的元数据")
        val metaSize = metaEntry.size.toInt()
        val metaBuf = ByteArrayOutputStream(metaSize)
        archive.getInputStream(metaEntry).use { it.copyAmountTo(metaBuf, metaSize.toLong()) }
        meta = VersionData(JSONObject(metaBuf.toByteArray().decodeToString()))
    }

    override fun iterator(): Iterator<PatchEntry>
    {
        return NewFileIterator(this)
    }

    override fun close()
    {
        archive.close()
    }

    class NewFileIterator(val reader: PatchFileReader) : Iterator<PatchEntry>
    {
        val iter = reader.meta.newFiles.iterator()

        override fun hasNext(): Boolean = iter.hasNext()

        override fun next(): PatchEntry = PatchEntry(reader, iter.next())
    }

    class PatchEntry(private val reader: PatchFileReader, val newFile: NewFile)
    {
        val mode = newFile.mode

        fun hasData(): Boolean
        {
            return mode == ModificationMode.Modify || mode == ModificationMode.Fill
        }

        fun getInputStream(): InputStream
        {
            if (!hasData())
                return EmptyInputStream()

            val entry = reader.archive.getEntry(newFile.path) ?: throw McPatchManagerException("[${reader.version}] 找不到文件数据: $newFile")

            val entryStream = reader.archive.getInputStream(entry)
            val bzipped = SHA1CheckInputStream(entryStream)
            val unbzipped = SHA1CheckInputStream(CBZip2InputStream(bzipped))

            return ActionedInputStream(unbzipped, newFile.rawLength.toInt()) {
                if (bzipped.digest() != newFile.bzippedHash)
                    throw McPatchManagerException("[${reader.version}] 更新包中 ${newFile.path} 文件的数据（bzipped）无法通过验证")

                if (unbzipped.digest() != newFile.rawHash)
                    throw McPatchManagerException("[${reader.version}] 更新包中 ${newFile.path} 文件的数据（unbzipped）无法通过验证")
            }
        }

        fun copyTo(output: OutputStream)
        {
            val input = getInputStream()
            input.copyAmountTo(output, newFile.rawLength)
        }
    }
}
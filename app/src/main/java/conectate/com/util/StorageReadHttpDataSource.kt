package conectate.com.util

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/** Guard each consumer read, including bytes already buffered by the HTTP transport. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StorageReadHttpDataSource(private val upstream: HttpDataSource) : HttpDataSource by upstream {
    private val guard = StorageReadDataSource(DataSource.Factory { upstream }, DataSource.Factory { upstream })
    private var currentSpec: DataSpec? = null
    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        return try { guard.open(dataSpec) }
        catch (error: IOException) { throw HttpDataSource.HttpDataSourceException.createForIOException(error, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try { guard.read(buffer, offset, length) }
    catch (error: IOException) {
        val spec = currentSpec ?: throw error
        throw HttpDataSource.HttpDataSourceException.createForIOException(error, spec, HttpDataSource.HttpDataSourceException.TYPE_READ)
    }
    override fun close() {
        val spec = currentSpec
        try { guard.close() }
        catch (error: IOException) {
            if (spec == null) throw error
            throw HttpDataSource.HttpDataSourceException.createForIOException(error, spec, HttpDataSource.HttpDataSourceException.TYPE_CLOSE)
        } finally { currentSpec = null }
    }
    internal class Factory(internal val upstream: HttpDataSource.Factory) : HttpDataSource.Factory {
        override fun createDataSource(): HttpDataSource = StorageReadHttpDataSource(upstream.createDataSource())
        override fun setDefaultRequestProperties(properties: Map<String, String>): HttpDataSource.Factory {
            upstream.setDefaultRequestProperties(properties)
            return this
        }
    }
}

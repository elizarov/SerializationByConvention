package automaticallyDerived2

import java.io.*
import kotlin.reflect.*

@Serializable(DataIO::class)
data class MaybeInt(val value: Int?)

object __MaybeIntDataIO : DataIO<MaybeInt> {
    override fun DataOutput.write(obj: MaybeInt) {
        writeNullableInt(obj.value)
    }

    override fun DataInput.read(): MaybeInt {
        val value = readNullableInt()
        return MaybeInt(value)
    }
}

// --- type-specific extension ---

fun DataOutput.writeNullableInt(obj: Int?) {
    if (obj == null) {
        writeByte(0)
    } else {
        writeByte(1)
        writeInt(obj)
    }
}

fun DataInput.readNullableInt(): Int? =
    if (readByte().toInt() == 0) null else readInt()

// --- test code ---

fun main(args: Array<String>) {
    val bytes = testWrite()
    testRead(bytes)
}

private fun testWrite(): ByteArray {
    val baos = ByteArrayOutputStream()
    val obj = MaybeInt(42) 
    val output = DataOutputStream(baos)
    output.write(obj, __MaybeIntDataIO)
    val bytes = baos.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    return bytes
}

private fun testRead(bytes: ByteArray) {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val obj = input.read(__MaybeIntDataIO)
    println(obj)
}


// --- i/o interfaces ---

interface DataOutputWriter<in T> {
    fun DataOutput.write(obj: T)
}

@InjectSerializer
fun <T> DataOutput.write(obj: T, writer: DataOutputWriter<T>) = with(writer) { write(obj) }

interface DataInputReader<out T> {
    fun DataInput.read(): T
}

@InjectSerializer
fun <T> DataInput.read(reader: DataInputReader<T>) = with(reader) { read() }

interface DataIO<T> : DataOutputWriter<T>, DataInputReader<T>

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer

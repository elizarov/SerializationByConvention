package automaticallyDerived

import java.io.*
import kotlin.reflect.*

@Serializable(DataIO::class)
data class MaybeInt(val value: Int?)

object __IntDataIO : DataOutputWriter<Int>, DataInputReader<Int> {
    override fun DataOutput.write(obj: Int) = writeInt(obj)
    override fun DataInput.read(): Int = readInt()
}

object __MaybeIntDataIO : DataIO<MaybeInt> {
    override fun DataOutput.write(obj: MaybeInt) {
        writeNullable(obj.value, __IntDataIO)
    }

    override fun DataInput.read(): MaybeInt {
        val value = readNullable(__IntDataIO)
        return MaybeInt(value)
    }
}


// --- nullable support ---

@nullableTypes.InjectSerializer
fun <T : Any> DataOutput.writeNullable(obj: T?, writer: DataOutputWriter<T>) {
    if (obj == null) {
        writeByte(0)
    } else {
        writeByte(1)
        with(writer) { write(obj) }
    }
}

@nullableTypes.InjectSerializer
fun <T : Any> DataInput.readNullable(reader: DataInputReader<T>): T? =
    if (readByte().toInt() == 0) null else with(reader) { read() }

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

annotation class Serializable(vararg val interfaces: KClass<*>)
annotation class InjectSerializer

package simpleBinary

import java.io.*
import kotlin.reflect.*

@Serializable(DataOutputWriter::class, DataInputReader::class)
data class Person(val name: String, val age: Int)

interface DataOutputWriter<in T> {
    fun DataOutput.write(obj: T)
}

object __PersonDataOutputWriter : DataOutputWriter<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
    }
}

@InjectSerializer
fun <T> DataOutput.write(obj: T, writer: DataOutputWriter<T>) = with(writer) { write(obj) }

interface DataInputReader<out T> {
    fun DataInput.read(): T
}

object __PersonDataInputReader : DataInputReader<Person> {
    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        return Person(name, age)
    }
}

@InjectSerializer
fun <T> DataInput.read(reader: DataInputReader<T>) = with(reader) { read() }

interface DataIO<T> : DataOutputWriter<T>, DataInputReader<T>

// --- test code ---

fun main(args: Array<String>) {
    val bytes = testWrite()
    testRead(bytes)
}

private fun testWrite(): ByteArray {
    val baos = ByteArrayOutputStream()
    val person = Person("Elvis", 42)
    val output = DataOutputStream(baos)
    output.write(person, __PersonDataOutputWriter)
    val bytes = baos.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    return bytes
}

private fun testRead(bytes: ByteArray) {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val person = input.read(__PersonDataInputReader)
    println(person)
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer

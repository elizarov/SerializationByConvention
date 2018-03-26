package objectGraph

import java.io.*
import kotlin.reflect.*

@Serializable(DataIO::class)
data class Date(val year: Int, val month: Int, val day: Int)

@Serializable(DataIO::class)
data class Person(val name: String, val age: Int, val born: Date, val dead: Date)

object __DateDataIO : DataIO<Date> {
    override fun DataOutput.write(obj: Date) {
        writeInt(obj.year)
        writeInt(obj.month)
        writeInt(obj.day)
    }

    override fun DataInput.read(): Date {
        val year = readInt()
        val month = readInt()
        val day = readInt()
        return Date(year, month, day)
    }
}

object __PersonDataIO : DataIO<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
        write(obj.born, __DateDataIO)
        write(obj.dead, __DateDataIO)
    }

    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        val born = read(__DateDataIO)
        val dead = read(__DateDataIO)
        return Person(name, age, born, dead)
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val bytes = testWrite()
    testRead(bytes)
}

private fun testWrite(): ByteArray {
    val baos = ByteArrayOutputStream()
    val person = Person("Elvis", 42, 
        born = Date(1935, 1, 8),
        dead = Date(1977, 8, 16))
    val output = DataOutputStream(baos)
    output.write(person, __PersonDataIO)
    val bytes = baos.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    return bytes
}

private fun testRead(bytes: ByteArray) {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val person = input.read(__PersonDataIO)
    println(person)
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

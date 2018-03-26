package collectionsAutomatic

import collectionsManual.*
import java.io.*
import kotlin.reflect.*

@Serializable(DataIO::class)
data class Person(val name: String, val age: Int)

@Serializable(DataIO::class)
data class Persons(val list: List<Person>)

object __PersonDataIO : DataIO<Person> {
    override fun DataOutput.write(obj: Person) {
        writeUTF(obj.name)
        writeInt(obj.age)
    }

    override fun DataInput.read(): Person {
        val name = readUTF()
        val age = readInt()
        return Person(name, age)
    }
}

object __PersonsDataIO : DataIO<Persons> {
    override fun DataOutput.write(obj: Persons) {
        write(obj.list, __ListDataOutputWriter(__PersonDataIO))
    }

    override fun DataInput.read(): Persons {
        val list = read(__ListDataInputReader(__PersonDataIO))
        return Persons(list)
    }
}

// --- generated list serializer --

class __ListDataOutputWriter<in T>(private val writer: DataOutputWriter<T>) : DataOutputWriter<List<T>> {
    override fun DataOutput.write(obj: List<T>) {
        val n = obj.size
        writeInt(n)
        for (i in 0 until n) write(obj[i], writer)
    }
}

class __ListDataInputReader<out T>(private val reader: DataInputReader<T>) : DataInputReader<List<T>> {
    override fun DataInput.read(): List<T> {
        val n = readInt()
        val list = ArrayList<T>(n)
        for (i in 0 until n) list += read(reader)
        return list
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val bytes = testWrite()
    testRead(bytes)
}

private fun testWrite(): ByteArray {
    val baos = ByteArrayOutputStream()
    val persons = Persons(listOf(
        Person("Elvis", 42),
        Person("Jesus", 33))
    )
    val output = DataOutputStream(baos)
    output.write(persons, __PersonsDataIO)
    val bytes = baos.toByteArray()
    println(bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    return bytes
}

private fun testRead(bytes: ByteArray) {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    val persons = input.read(__PersonsDataIO)
    println(persons)
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


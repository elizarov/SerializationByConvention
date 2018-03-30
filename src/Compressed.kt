package compressed

import java.io.*
import kotlin.reflect.*

@Serializable(CompressedWriter::class)
class Person(val name: String, val age: Int)

@Serializable(ObjectNotationWriter::class)
class Persons(val list: List<Person>)

interface ObjectNotationWriter<T> {
    fun PrintWriter.write(obj: T)
}

interface CompressedWriter<T> {
    val descriptor: List<String>
    fun PrintWriter.write(obj: T)
}

fun buildDescriptor(block: MutableList<String>.() -> Unit): List<String> =
    mutableListOf<String>().apply { block() }

fun MutableList<String>.describeProperty(name: String) {
    add(name)
}

// --- These operators are specific for ObjectNotationWriter

@InjectSerializer
@SerializationOperator(ObjectNotationWriter::class)
fun <T> PrintWriter.write(obj: T, writer: ObjectNotationWriter<T>) = with(writer) { write(obj) }

@SerializationOperator(ObjectNotationWriter::class)
fun PrintWriter.writeValue(value: Any?) = print(value)


@SerializationOperator(ObjectNotationWriter::class)
fun PrintWriter.child(name: String) = this.also { print("$name:") }

@SerializationOperator(ObjectNotationWriter::class)
fun PrintWriter.beginWrite() = print("{")

@SerializationOperator(ObjectNotationWriter::class)
fun PrintWriter.nextWrite() = print(",")

@SerializationOperator(ObjectNotationWriter::class)
fun PrintWriter.endWrite() = print("}")

@InjectSerializer
@SerializationOperator(ObjectNotationWriter::class)
fun <T> PrintWriter.writeList(obj: List<T>, writer: CompressedWriter<T>) {
    print("[")
    var sep = ""
    for (name in writer.descriptor) {
        print(sep)
        print(name)
        sep = ","

    }
    for (item in obj) {
        write(item, writer)
    }
    print("]")
}

// --- These operators are specific for CompressedWriter

@SerializationOperator(CompressedWriter::class)
fun PrintWriter.writeCompressed(value: Any?) {
    print(",")
    print(value)
}

@InjectSerializer
@SerializationOperator(CompressedWriter::class)
fun <T> PrintWriter.write(obj: T, writer: CompressedWriter<T>) = with(writer) { write(obj) }

// ---

object __PersonCompressedWriter : CompressedWriter<Person> {
    override val descriptor: List<String> = buildDescriptor {
        describeProperty("name")
        describeProperty("age")
    }

    override fun PrintWriter.write(obj: Person) {
        writeCompressed(obj.name)
        writeCompressed(obj.age)
    }
}

object __PersonsObjectNotationWriter : ObjectNotationWriter<Persons> {
    override fun PrintWriter.write(obj: Persons) {
        beginWrite()
        child("list").writeList(obj.list, __PersonCompressedWriter)
        endWrite()
    }
}

// --- test code ---

fun main(args: Array<String>) {
    val sw = StringWriter()
    val output = PrintWriter(sw)
    val persons = Persons(listOf(
        Person("Elvis", 42),
        Person("Jesus", 33))
    )
    output.write(persons, __PersonsObjectNotationWriter)
    println(sw)
}

// ---- aux annotations ---

annotation class Serializable(vararg val serializers: KClass<*>)
annotation class InjectSerializer
annotation class SerializationOperator(vararg val serializers: KClass<*>)